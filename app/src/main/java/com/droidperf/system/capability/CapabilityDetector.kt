package com.droidperf.system.capability

import android.os.Build
import com.droidperf.domain.AccessLevel
import com.droidperf.system.SysFs

/**
 * Probes the actual device to build a capability matrix. Nothing here is assumed from
 * the Android version alone: every flag is the result of a real read or a real
 * permission check, so the diagnostics screen tells the truth.
 */
class CapabilityDetector(
    private val shellAvailable: (AccessLevel) -> Boolean,
) {

    fun detect(accessLevel: AccessLevel, hasUsageAccess: Boolean): Capabilities {
        val hasShell = accessLevel != AccessLevel.STANDARD || shellAvailable(accessLevel)

        return Capabilities(
            accessLevel = accessLevel,
            // Frame statistics require shell/root on modern Android.
            fps = hasShell,
            gpuUsage = gpuUsageAvailable(),
            gpuFrequency = gpuFrequencyAvailable(),
            gpuTemperature = gpuTemperatureAvailable(),
            cpuFrequency = SysFs.exists("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"),
            cpuTemperature = hasThermalZone(listOf("cpu", "soc", "ap", "cluster")),
            perCoreCpu = SysFs.exists("/proc/stat"),
            temperature = SysFs.exists("/sys/class/thermal") ||
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N,
            processStats = hasShell,
            foregroundApp = hasUsageAccess || hasShell,
        )
    }

    private fun gpuUsageAvailable(): Boolean {
        if (SysFs.exists("/sys/class/kgsl/kgsl-3d0/gpubusy")) return true
        return SysFs.listDir("/sys/class/devfreq").any { dir ->
            SysFs.exists("/sys/class/devfreq/$dir/load") ||
                SysFs.exists("/sys/class/devfreq/$dir/gpu_load")
        }
    }

    private fun gpuFrequencyAvailable(): Boolean {
        if (SysFs.exists("/sys/class/kgsl/kgsl-3d0/gpuclk")) return true
        return SysFs.listDir("/sys/class/devfreq").any { dir ->
            SysFs.exists("/sys/class/devfreq/$dir/cur_freq")
        }
    }

    private fun gpuTemperatureAvailable() = hasThermalZone(listOf("gpu", "kgsl", "mali"))

    private fun hasThermalZone(keywords: List<String>): Boolean {
        val base = "/sys/class/thermal"
        return SysFs.listDir(base)
            .filter { it.startsWith("thermal_zone") }
            .any { z ->
                val type = SysFs.readText("$base/$z/type")?.lowercase() ?: return@any false
                keywords.any { type.contains(it) }
            }
    }

    /** Human-readable SoC line for the diagnostics header. */
    fun socDescription(): String = buildString {
        append(Build.HARDWARE.ifBlank { "unknown" })
        SysFs.readText("/sys/devices/soc0/machine")?.let { append(" ($it)") }
    }
}
