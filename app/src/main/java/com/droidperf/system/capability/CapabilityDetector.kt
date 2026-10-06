package com.droidperf.system.capability

import android.os.Build
import com.droidperf.domain.AccessLevel
import com.droidperf.system.SysFs

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
        if (SysFs.exists("/proc/ged/hal/gpu_utilization") ||
            SysFs.exists("/proc/ged/hal/loading") ||
            SysFs.exists("/proc/ged/hal/gpu_loading")) return true
        if (SysFs.exists("/sys/class/misc/sgpu/device/gpu_busy_percent") ||
            SysFs.exists("/sys/devices/platform/17000000.sgpu/gpu_busy_percent")) return true
        return SysFs.listDir("/sys/class/devfreq").any { dir ->
            SysFs.exists("/sys/class/devfreq/$dir/load") ||
                SysFs.exists("/sys/class/devfreq/$dir/gpu_load")
        }
    }

    private fun gpuFrequencyAvailable(): Boolean {
        if (SysFs.exists("/sys/class/kgsl/kgsl-3d0/gpuclk")) return true
        if (SysFs.exists("/proc/ged/hal/current_freq") ||
            SysFs.exists("/proc/ged/hal/gpu_cur_freq")) return true
        if (SysFs.exists("/sys/devices/platform/17000000.sgpu/devfreq/17000000.sgpu/cur_freq")) return true
        return SysFs.listDir("/sys/class/devfreq").any { dir ->
            SysFs.exists("/sys/class/devfreq/$dir/cur_freq")
        }
    }

    private fun gpuTemperatureAvailable() = hasThermalZone(listOf("gpu", "kgsl", "mali", "sgpu", "g3d", "gpuss"))

    private fun hasThermalZone(keywords: List<String>): Boolean {
        val base = "/sys/class/thermal"
        return SysFs.listDir(base)
            .filter { it.startsWith("thermal_zone") }
            .any { z ->
                val type = SysFs.readText("$base/$z/type")?.lowercase() ?: return@any false
                keywords.any { type.contains(it) }
            }
    }

    @Volatile
    private var cachedSocDesc: String? = null

    /** Human-readable SoC line for the diagnostics header. */
    fun socDescription(): String {
        cachedSocDesc?.let { return it }
        val hw = Build.HARDWARE.trim()
        val board = Build.BOARD.trim()
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.trim() else ""
        val machine = SysFs.readText("/sys/devices/soc0/machine")?.trim()

        val resolvedName = when {
            machine != null && machine.isNotEmpty() && machine != "Unknown" ->
                machine
            socModel.isNotEmpty() ->
                if (hw.isNotEmpty() && hw.lowercase() != "unknown" && !hw.equals(socModel, ignoreCase = true)) {
                    "$socModel ($hw)"
                } else socModel
            hw.isNotEmpty() && hw.lowercase() != "unknown" ->
                if (board.isNotEmpty() && board.lowercase() != "unknown" && !board.equals(hw, ignoreCase = true)) {
                    "$hw ($board)"
                } else hw
            else ->
                board.ifBlank { "Generic SoC" }
        }
        cachedSocDesc = resolvedName
        return resolvedName
    }
}

