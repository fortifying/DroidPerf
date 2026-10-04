package com.droidperf.monitoring.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.droidperf.domain.Metric
import com.droidperf.system.shell.SafeCommands
import com.droidperf.system.shell.Shell

/**
 * Foreground package detection.
 *
 * Primary: UsageStatsManager (needs the user-granted "Usage access" special permission,
 * no root). We query the last few seconds of events and take the most recent
 * ACTIVITY_RESUMED, which is the documented, non-deprecated path on Android 10+.
 *
 * Fallback: `dumpsys activity activities` via shell, used when usage access is not
 * granted but Shizuku/root is available.
 */
class ForegroundAppDetector(
    private val context: Context,
    private val shellProvider: () -> Shell?,
) {

    fun hasUsageAccess(): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        val mode = appOps?.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) {
        false
    }

    fun detect(): Metric<String> {
        // Ignore our own overlay package and system UI so we report the game, not us.
        val ignore = setOf(
            context.packageName,
            "com.android.systemui",
            "android",
        )

        if (hasUsageAccess()) {
            usageStatsForeground()?.let { pkg ->
                if (pkg !in ignore) return Metric.Available(pkg)
            }
        }

        shellForeground()?.let { pkg ->
            if (pkg !in ignore) return Metric.Available(pkg)
        }

        return Metric.Unavailable(
            if (!hasUsageAccess())
                "Foreground app needs Usage access (Settings > Special access > Usage access)"
            else "No foreground package reported"
        )
    }

    private fun usageStatsForeground(): String? {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null
        val end = System.currentTimeMillis()
        val begin = end - 10_000
        val events = usm.queryEvents(begin, end)
        var lastPkg: String? = null
        var lastTs = 0L
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val isResumed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            } else {
                @Suppress("DEPRECATION")
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            }
            if (isResumed && event.timeStamp >= lastTs) {
                lastTs = event.timeStamp
                lastPkg = event.packageName
            }
        }
            lastPkg
        } catch (_: Throwable) {
            null
        }
    }

    private fun shellForeground(): String? {
        val shell = shellProvider() ?: return null
        val res = shell.exec(SafeCommands.FOREGROUND_ACTIVITY) ?: return null
        if (!res.ok) return null
        val line = res.stdout.firstOrNull { it.contains("ResumedActivity") } ?: return null
        val match = RESUMED_PKG_REGEX.find(line) ?: return null
        return match.groupValues.getOrNull(1)
    }

    private companion object {
        val RESUMED_PKG_REGEX = Regex("([a-zA-Z0-9_.]+)/")
    }
}
