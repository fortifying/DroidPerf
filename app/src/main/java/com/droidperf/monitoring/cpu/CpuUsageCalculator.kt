package com.droidperf.monitoring.cpu

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
