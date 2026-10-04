package com.droidperf.monitoring.memory

import android.app.ActivityManager
import android.content.Context
import com.droidperf.domain.Metric
import com.droidperf.system.SysFs

/**
 * System-wide RAM, deliberately NOT the app's own heap.
 *
 * Total/available come from ActivityManager.MemoryInfo (the kernel's own accounting).
 * "Used" is total - available, which is what the system considers non-reclaimable.
 * Cached is parsed from /proc/meminfo when readable.
 */
class RamMonitor(private val context: Context) {

    data class Ram(
        val used: Metric<Long>,
        val total: Metric<Long>,
        val available: Metric<Long>,
        val cached: Metric<Long>,
    )

    fun sample(): Ram {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Ram(
                Metric.Unavailable("ActivityManager unavailable"),
                Metric.Unavailable("ActivityManager unavailable"),
                Metric.Unavailable("ActivityManager unavailable"),
                Metric.Unavailable("ActivityManager unavailable"),
            )

        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)

        val totalBytes = info.totalMem
        val availBytes = info.availMem

        val total = if (totalBytes > 0) Metric.Available(totalBytes)
        else Metric.Unavailable("system did not report total memory")
        val available = if (availBytes > 0) Metric.Available(availBytes)
        else Metric.Unavailable("system did not report available memory")
        val used = if (totalBytes > 0 && availBytes > 0)
            Metric.Available(totalBytes - availBytes)
        else Metric.Unavailable("cannot derive used memory without total and available")

        return Ram(used, total, available, sampleCached())
    }

    private fun sampleCached(): Metric<Long> {
        val text = SysFs.readText("/proc/meminfo")
            ?: return Metric.Unavailable("/proc/meminfo not readable")
        // "Cached:" and "SReclaimable:" are both reclaimable page cache; sum them like
        // the `free` tool does so the number matches what users expect.
        var cachedKb = 0L
        var sReclaimKb = 0L
        text.lineSequence().forEach { line ->
            when {
                line.startsWith("Cached:") -> cachedKb = line.kb()
                line.startsWith("SReclaimable:") -> sReclaimKb = line.kb()
            }
        }
        val totalKb = cachedKb + sReclaimKb
        return if (totalKb > 0) Metric.Available(totalKb * 1024L)
        else Metric.Unavailable("Cached field not present in /proc/meminfo")
    }

    private fun String.kb(): Long =
        split(WHITESPACE_REGEX).getOrNull(1)?.toLongOrNull() ?: 0L

    private companion object {
        val WHITESPACE_REGEX = Regex("\\s+")
    }
}
