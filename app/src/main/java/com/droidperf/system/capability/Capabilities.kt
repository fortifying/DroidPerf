package com.droidperf.system.capability

import com.droidperf.domain.AccessLevel

/**
 * What a provider can actually deliver. Every field is probed at runtime against the
 * real device, never assumed from the Android version alone.
 */
data class Capabilities(
    val accessLevel: AccessLevel,
    val fps: Boolean,
    val gpuUsage: Boolean,
    val gpuFrequency: Boolean,
    val gpuTemperature: Boolean,
    val cpuFrequency: Boolean,
    val cpuTemperature: Boolean,
    val perCoreCpu: Boolean,
    val temperature: Boolean,
    val processStats: Boolean,
    val foregroundApp: Boolean,
) {
    /** Human readable explanation map for the diagnostics screen. */
    fun describe(metric: String): String = when (metric) {
        "fps" -> if (fps) "Target-app FPS readable via frame statistics." else
            "No frame-statistics interface reachable at this access level."
        "gpuUsage" -> if (gpuUsage) "GPU utilization exposed by a vendor sysfs node." else
            "Unavailable on this device because no accessible GPU utilization interface was detected."
        "gpuFrequency" -> if (gpuFrequency) "GPU clock exposed via devfreq/kgsl." else
            "No GPU frequency node exposed."
        "gpuTemperature" -> if (gpuTemperature) "GPU thermal zone matched by label." else
            "No thermal zone could be confidently matched to the GPU."
        "cpuFrequency" -> if (cpuFrequency) "CPU scaling nodes readable." else
            "CPU frequency nodes not readable at this access level."
        "cpuTemperature" -> if (cpuTemperature) "A CPU thermal zone was matched by label." else
            "No thermal zone could be confidently matched to the CPU."
        "perCoreCpu" -> if (perCoreCpu) "Per-core /proc/stat lines readable." else
            "Per-core CPU counters not readable."
        "temperature" -> if (temperature) "Thermal zones readable." else
            "Thermal sysfs not readable at this access level."
        "processStats" -> if (processStats) "Per-process stats available." else
            "Per-process stats need Shizuku or root."
        "foregroundApp" -> if (foregroundApp) "Foreground package detectable." else
            "Usage access not granted, so the foreground package cannot be read."
        else -> "Unknown metric."
    }
}
