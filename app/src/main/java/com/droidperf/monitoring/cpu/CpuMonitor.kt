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
     * Current frequency per policy, in MHz. Modern kernels expose
     * /sys/devices/system/cpu/cpuN/cpufreq/scaling_cur_freq in kHz.
     */
    fun sampleFrequenciesMhz(): Metric<List<Int>> {
        val coreCount = SysFs.listDir("/sys/devices/system/cpu")
            .count { it.matches(CPU_DIR_REGEX) }
        if (coreCount == 0) return Metric.Unavailable("no CPU directories found")

        val freqs = (0 until coreCount).mapNotNull { n ->
            val base = "/sys/devices/system/cpu/cpu$n/cpufreq"
            val khz = SysFs.readLong("$base/scaling_cur_freq")
                ?: SysFs.readLong("$base/cpuinfo_cur_freq")
                ?: return@mapNotNull null
            (khz / 1000L).toInt()
        }
        return if (freqs.isEmpty())
            Metric.Unavailable("cpufreq scaling nodes not readable at this access level")
        else Metric.Available(freqs)
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
