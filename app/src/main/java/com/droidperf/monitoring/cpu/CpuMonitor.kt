package com.droidperf.monitoring.cpu

import com.droidperf.domain.Metric
import com.droidperf.system.SysFs

/**
 * Reads real CPU state. Utilization is always computed from two /proc/stat samples,
 * so the first call intentionally reports "warming up" rather than a guessed value.
 *
 * Core count is detected at runtime; nothing assumes a fixed number of cores.
 */
class CpuMonitor {

    private var prevTicks: Map<String, CpuTicks> = emptyMap()

    /** All cpu lines from /proc/stat: "cpu" plus each "cpuN". */
    private fun readTicks(): Map<String, CpuTicks> {
        val text = SysFs.readText("/proc/stat") ?: return emptyMap()
        val out = LinkedHashMap<String, CpuTicks>()
        text.lineSequence()
            .takeWhile { it.startsWith("cpu") }
            .forEach { line ->
                CpuTicks.parse(line)?.let { (label, ticks) -> out[label] = ticks }
            }
        return out
    }

    data class Usage(
        val total: Metric<Double>,
        val perCore: Metric<List<Double>>,
    )

    fun sampleUsage(): Usage {
        val curr = readTicks()
        if (curr.isEmpty()) {
            return Usage(
                Metric.Unavailable("/proc/stat is not readable on this device"),
                Metric.Unavailable("/proc/stat is not readable on this device"),
            )
        }
        val prev = prevTicks
        prevTicks = curr

        if (prev.isEmpty()) {
            return Usage(
                Metric.Unavailable("warming up: first /proc/stat sample taken"),
                Metric.Unavailable("warming up: first /proc/stat sample taken"),
            )
        }

        val total = CpuUsageCalculator.aggregate(prev, curr)
            ?.let { Metric.Available(it) }
            ?: Metric.Unavailable("no CPU time elapsed between samples")

        val cores = CpuUsageCalculator.perCore(prev, curr)
        val perCore = if (cores.isEmpty())
            Metric.Unavailable("per-core counters unavailable") else Metric.Available(cores)

        return Usage(total, perCore)
    }

    /**
     * Current frequency per CPU core, in MHz. Supports both legacy per-core
     * nodes (`/sys/devices/system/cpu/cpuN/cpufreq`) and modern cluster policy nodes
     * (`/sys/devices/system/cpu/cpufreq/policyN`).
     */
    fun sampleFrequenciesMhz(): Metric<List<Int>> {
        val coreCount = SysFs.listDir("/sys/devices/system/cpu")
            .count { it.matches(CPU_DIR_REGEX) }
        if (coreCount == 0) return Metric.Unavailable("no CPU directories found")

        // First attempt: per-cpu directory
        val freqs = (0 until coreCount).mapNotNull { n ->
            val base = "/sys/devices/system/cpu/cpu$n/cpufreq"
            val khz = SysFs.readLong("$base/scaling_cur_freq")
                ?: SysFs.readLong("$base/cpuinfo_cur_freq")
                ?: return@mapNotNull null
            (khz / 1000L).toInt()
        }
        if (freqs.size == coreCount) return Metric.Available(freqs)

        // Second attempt: cluster policy nodes with core mapping
        val policyDirs = SysFs.listDir("/sys/devices/system/cpu/cpufreq")
            .filter { it.startsWith("policy") }
            .sorted()
        if (policyDirs.isNotEmpty()) {
            val coreFreqs = IntArray(coreCount) { 0 }
            var anyFound = false
            for (p in policyDirs) {
                val pBase = "/sys/devices/system/cpu/cpufreq/$p"
                val khz = SysFs.readLong("$pBase/scaling_cur_freq")
                    ?: SysFs.readLong("$pBase/cpuinfo_cur_freq")
                    ?: continue
                val mhz = (khz / 1000L).toInt()
                val cpusText = SysFs.readText("$pBase/affected_cpus")
                    ?: SysFs.readText("$pBase/related_cpus")
                if (cpusText != null) {
                    val indices = cpusText.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                    for (idx in indices) {
                        if (idx in 0 until coreCount) {
                            coreFreqs[idx] = mhz
                            anyFound = true
                        }
                    }
                } else {
                    val pIndex = p.removePrefix("policy").toIntOrNull()
                    if (pIndex != null && pIndex in 0 until coreCount) {
                        coreFreqs[pIndex] = mhz
                        anyFound = true
                    }
                }
            }
            if (anyFound) {
                val resultList = coreFreqs.mapIndexed { idx, freq ->
                    if (freq > 0) freq else freqs.getOrNull(idx) ?: 0
                }.filter { it > 0 }
                if (resultList.isNotEmpty()) return Metric.Available(resultList)
            }
        }

        return if (freqs.isNotEmpty()) Metric.Available(freqs)
        else Metric.Unavailable("cpufreq scaling nodes not readable at this access level")
    }

    fun sampleGovernor(): Metric<String> {
        val path = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"
        return SysFs.readText(path)?.let { Metric.Available(it) }
            ?: Metric.Unavailable("governor node not readable")
    }

    /** Number of online cores, detected, never assumed. */
    fun coreCount(): Int =
        SysFs.listDir("/sys/devices/system/cpu").count { it.matches(CPU_DIR_REGEX) }

    private companion object {
        val CPU_DIR_REGEX = Regex("cpu\\d+")
    }
}

/**
 * One /proc/stat "cpuN" line's jiffie counters. Kept as a plain data class so the
 * utilization math is a pure function and can be unit tested without a device.
 */
data class CpuTicks(
    val user: Long,
    val nice: Long,
    val system: Long,
    val idle: Long,
    val iowait: Long,
    val irq: Long,
    val softirq: Long,
    val steal: Long,
) {
    val idleAll: Long get() = idle + iowait
    val total: Long get() = user + nice + system + irq + softirq + steal + idleAll

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")

        /**
         * Parse a single "/proc/stat" cpu line. Returns null for the aggregate "cpu "
         * header or malformed lines so callers never invent a core.
         *
         * Format: cpu[N] user nice system idle iowait irq softirq steal guest guest_nice
         */
        fun parse(line: String): Pair<String, CpuTicks>? {
            val parts = line.trim().split(WHITESPACE_REGEX)
            if (parts.size < 5) return null
            val label = parts[0]
            if (!label.startsWith("cpu")) return null
            fun at(i: Int): Long = parts.getOrNull(i)?.toLongOrNull() ?: 0L
            val ticks = CpuTicks(
                user = at(1),
                nice = at(2),
                system = at(3),
                idle = at(4),
                iowait = at(5),
                irq = at(6),
                softirq = at(7),
                steal = at(8),
            )
            return label to ticks
        }
    }
}

/**
 * Pure utilization math. A snapshot alone is meaningless, so we always diff two
 * samples and divide busy jiffies by elapsed jiffies.
 */
object CpuUsageCalculator {

    /** Percent busy (0..100) between two samples of the same core. Null if no time passed. */
    fun usage(prev: CpuTicks, curr: CpuTicks): Double? {
        val totalDelta = curr.total - prev.total
        if (totalDelta <= 0) return null
        val idleDelta = curr.idleAll - prev.idleAll
        val busy = totalDelta - idleDelta
        return (busy.toDouble() / totalDelta.toDouble() * 100.0).coerceIn(0.0, 100.0)
    }

    /** Aggregate busy percent across all cores, using the "cpu" summary line. */
    fun aggregate(prev: Map<String, CpuTicks>, curr: Map<String, CpuTicks>): Double? {
        val prevTotal = prev["cpu"] ?: return null
        val currTotal = curr["cpu"] ?: return null
        return usage(prevTotal, currTotal)
    }

    /** Per-core busy percent, keyed by "cpuN", excluding the aggregate line. */
    fun perCore(prev: Map<String, CpuTicks>, curr: Map<String, CpuTicks>): List<Double> =
        curr.keys
            .filter { it != "cpu" && it.length > 3 }
            .sortedBy { it.removePrefix("cpu").toIntOrNull() ?: Int.MAX_VALUE }
            .mapNotNull { key ->
                val p = prev[key] ?: return@mapNotNull null
                usage(p, curr[key]!!)
            }
}

