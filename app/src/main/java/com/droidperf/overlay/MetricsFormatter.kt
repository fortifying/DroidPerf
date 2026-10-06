package com.droidperf.overlay

import com.droidperf.domain.Metric
import com.droidperf.domain.MetricsSnapshot
import com.droidperf.domain.unavailableReason
import com.droidperf.domain.valueOrNull
import com.droidperf.settings.OverlayConfig

/** One overlay text line and the color it is drawn in (styled renderers such as RTSS). */
data class OsdLine(val text: String, val color: Int)

/** One horizontal badge/chip item for Modern HUD style. */
data class ModernItem(val label: String, val value: String, val color: Int)

/** One stat item in an RTSS row (value + unit + color + fixed column slot). */
data class RtssStat(
    val value: String,
    val unit: String,
    val color: Int,
    val slot: Int = 0, // 0 = Col 1 (Temp/VRAM/RAM/FPS), 1 = Col 2 (Usage/FT), 2 = Col 3 (Clock)
)

/** One horizontal multi-metric row for RTSS OSD style matching the RivaTuner reference layout. */
data class RtssRow(
    val label: String,
    val labelColor: Int,
    val stats: List<RtssStat>,
) {
    val text: String get() = toFormattedText()

    fun toFormattedText(): String {
        val sb = StringBuilder()
        sb.append(label.padEnd(6))
        var currentSlot = 0
        for (stat in stats.sortedBy { it.slot }) {
            while (currentSlot < stat.slot) {
                val emptyWidth = when (currentSlot) {
                    0 -> 9
                    1 -> 8
                    else -> 9
                }
                sb.append(" ".repeat(emptyWidth + 1))
                currentSlot++
            }
            val numStr = stat.value.padStart(4)
            val unitStr = stat.unit
            val colText = if (unitStr.isNotEmpty()) "$numStr $unitStr" else numStr
            val colTargetWidth = when (stat.slot) {
                0 -> 9
                1 -> 8
                else -> 9
            }
            sb.append(colText.padEnd(colTargetWidth))
            sb.append(" ")
            currentSlot++
        }
        return sb.toString().trimEnd()
    }
}

/**
 * Pure formatting of a [MetricsSnapshot] into overlay lines. Keeping this free of any
 * Android view code makes it directly unit-testable and guarantees the "N/A + reason"
 * rule is applied uniformly: an unavailable metric always renders as N/A, never a number.
 */
object MetricsFormatter {

    private const val NA = "N/A"
    /** Bytes -> "6.8G" style, binary units, one decimal. */
    fun bytes(n: Long): String {
        val gb = n / 1_073_741_824.0
        if (gb >= 1.0) return String.format("%.1fG", gb)
        val mb = n / 1_048_576.0
        return String.format("%.0fM", mb)
    }

    fun speed(bytesPerSec: Long): String {
        val mb = bytesPerSec / 1_048_576.0
        return if (mb >= 1.0) String.format("%.1f MB/s", mb)
        else String.format("%.0f KB/s", bytesPerSec / 1024.0)
    }

    private fun <T> fmt(m: Metric<T>, block: (T) -> String): String =
        m.valueOrNull()?.let(block) ?: NA

    private fun pct(m: Metric<Double>): String = fmt(m) { String.format("%.0f%%", it) }
    private fun pct1(m: Metric<Double>): String = fmt(m) { String.format("%.1f%%", it) }

    /** Detailed, one metric per line. */
    fun detailed(s: MetricsSnapshot, cfg: OverlayConfig): List<String> {
        val out = ArrayList<String>(20)

        if (cfg.showFps) {
            out += "FPS      ${fmt(s.fps) { String.format("%.1f", it) }}"
            if (cfg.showFrameTime) {
                out += "Frame    ${fmt(s.frameTimeMs) { String.format("%.1f", it) }} ms"
            }
            out += "Display  ${fmt(s.displayRefreshRateHz) { String.format("%.0f", it) }} Hz"
        }
        if (cfg.showCpu) {
            out += "CPU      ${pct(s.cpuTotalPercent)}"
            if (cfg.showPerCore) {
                s.perCorePercent.valueOrNull()?.forEachIndexed { i, v ->
                    out += String.format("C%-7d %.0f%%", i, v)
                }
            }
        }
        if (cfg.showGpu) {
            out += "GPU      ${pct(s.gpuUsagePercent)}"
            out += "GPU clk  ${fmt(s.gpuFreqMhz) { "$it MHz" }}"
        }
        if (cfg.showRam) {
            val used = s.ramUsedBytes.valueOrNull()
            val total = s.ramTotalBytes.valueOrNull()
            val ramLine = if (used != null && total != null) {
                "${bytes(used)}/${bytes(total)}"
            } else NA
            out += "RAM      $ramLine"
            if (total != null && used != null && total > 0) {
                out += String.format("RAM      %.0f%%", used.toDouble() / total * 100.0)
            }
        }
        if (cfg.showCpuTemp) {
            out += "CPU temp ${fmt(s.cpuTempC) { String.format("%.0f\u00B0C", it) }}"
        }
        if (cfg.showBatteryTemp) {
            out += "BAT temp ${fmt(s.batteryTempC) { String.format("%.1f\u00B0C", it) }}"
        }
        if (cfg.showBattery) {
            out += "BAT      ${fmt(s.batteryLevelPercent) { "$it%" }}"
            if (s.batteryPowerW.valueOrNull() != null) {
                out += "PWR      ${fmt(s.batteryPowerW) { String.format("%.2f W", it) }}"
            }
        }
        if (cfg.showNetwork) {
            out += "\u2193        ${fmt(s.netDownBytesPerSec) { speed(it) }}"
            out += "\u2191        ${fmt(s.netUpBytesPerSec) { speed(it) }}"
            if (cfg.latencyEnabled) out += "Ping     ${fmt(s.netPingMs) { "$it ms" }}"
        }
        if (cfg.showDisplay) {
            val w = s.screenWidth.valueOrNull(); val h = s.screenHeight.valueOrNull()
            out += "Res      ${if (w != null && h != null) "${w}x$h" else NA}"
        }
        if (cfg.showApp) {
            out += "APP      ${fmt(s.foregroundPackage) { it }}"
        }
        return out
    }

    /** Compact, grouped lines like a PC gaming overlay. */
    fun compact(s: MetricsSnapshot, cfg: OverlayConfig): List<String> {
        val out = ArrayList<String>(8)
        if (cfg.showFps) out += "FPS ${fmt(s.fps) { String.format("%.0f", it) }}"
        if (cfg.showCpu) out += "CPU ${pct(s.cpuTotalPercent)}"
        if (cfg.showCpuTemp) out += "CPU ${fmt(s.cpuTempC) { String.format("%.0f\u00B0C", it) }}"
        if (cfg.showGpu) out += "GPU ${pct(s.gpuUsagePercent)}"
        if (cfg.showRam) {
            val used = s.ramUsedBytes.valueOrNull(); val total = s.ramTotalBytes.valueOrNull()
            out += "RAM ${if (used != null && total != null) "${bytes(used)}/${bytes(total)}" else NA}"
        }
        if (cfg.showBatteryTemp) out += "BAT ${fmt(s.batteryTempC) { String.format("%.1f\u00B0C", it) }}"
        if (cfg.showBattery) out += "BAT% ${fmt(s.batteryLevelPercent) { "$it%" }}"
        return out
    }

    /** Modern horizontal pill HUD with individual colored chips. */
    fun modern(s: MetricsSnapshot, cfg: OverlayConfig): List<ModernItem> {
        val out = ArrayList<ModernItem>(8)
        if (cfg.showFps) {
            val fpsStr = fmt(s.fps) { String.format("%.0f", it) }
            out += ModernItem("FPS", fpsStr, COLOR_FPS_MODERN)
        }
        if (cfg.showFrameTime && s.frameTimeMs.valueOrNull() != null) {
            val ftStr = fmt(s.frameTimeMs) { String.format("%.1f ms", it) }
            out += ModernItem("FT", ftStr, COLOR_FPS_MODERN)
        }
        if (cfg.showCpu) {
            out += ModernItem("CPU", pct(s.cpuTotalPercent), COLOR_CPU_MODERN)
        }
        if (cfg.showCpuTemp) {
            val cpuTemp = s.cpuTempC.valueOrNull()
            val tempStr = cpuTemp?.let { String.format("%.0f\u00B0C", it) } ?: NA
            out += ModernItem("CPU T", tempStr, COLOR_CPU_TEMP_MODERN)
        }
        if (cfg.showGpu) {
            out += ModernItem("GPU", pct(s.gpuUsagePercent), COLOR_GPU_MODERN)
        }
        if (cfg.showRam) {
            val used = s.ramUsedBytes.valueOrNull()
            val ramStr = if (used != null) {
                String.format("%.1f GB", used / 1_073_741_824.0)
            } else NA
            out += ModernItem("RAM", ramStr, COLOR_RAM_MODERN)
        }
        if (cfg.showBatteryTemp) {
            val batTemp = s.batteryTempC.valueOrNull()
            val tempStr = batTemp?.let { String.format("%.1f\u00B0C", it) } ?: NA
            out += ModernItem("BAT", tempStr, COLOR_BAT_TEMP_MODERN)
        }
        return out
    }

    /** Minimal ultra-compact single badge string. */
    fun minimal(s: MetricsSnapshot, cfg: OverlayConfig): List<String> {
        val parts = mutableListOf<String>()
        if (cfg.showFps) parts += "${fmt(s.fps) { String.format("%.0f", it) }} FPS"
        if (cfg.showCpu) parts += "${pct(s.cpuTotalPercent)} CPU"
        if (cfg.showGpu) parts += "${pct(s.gpuUsagePercent)} GPU"
        if (cfg.showRam) {
            val used = s.ramUsedBytes.valueOrNull()
            if (used != null) parts += "${String.format("%.1fG", used / 1_073_741_824.0)} RAM"
        }
        if (cfg.showCpuTemp) {
            s.cpuTempC.valueOrNull()?.let { parts += "${String.format("%.0f\u00B0C", it)} CPU" }
        }
        if (cfg.showBatteryTemp) {
            s.batteryTempC.valueOrNull()?.let { parts += "${String.format("%.1f\u00B0C", it)} BAT" }
        }
        return listOf(parts.joinToString("  •  "))
    }

    fun render(s: MetricsSnapshot, cfg: OverlayConfig): List<String> =
        if (cfg.compactMode) compact(s, cfg) else detailed(s, cfg)

    /**
     * Authentic RivaTuner Statistics Server (RTSS) multi-column OSD.
     * Matches the reference layout:
     * GPU   64 °C   95 %   1755 MHz
     * MEM  2235 MB
     * CPU   37 °C   22 %   5094 MHz
     * RAM  8321 MB
     * D3D11 248 FPS 3.9 ms
     */
    fun rtss(s: MetricsSnapshot, cfg: OverlayConfig): List<RtssRow> {
        val out = ArrayList<RtssRow>(6)
        val gpuWanted = cfg.showGpu || cfg.showTemperature

        if (gpuWanted) {
            val gpuStats = mutableListOf<RtssStat>()
            if (cfg.showTemperature) {
                val tempStr = s.gpuTempC.valueOrNull()?.let { String.format("%.0f", it) } ?: NA
                val tempCol = if (tempStr == NA) COLOR_RTSS_NA else COLOR_RTSS_STAT
                gpuStats.add(RtssStat(tempStr, if (tempStr == NA) "" else "°C", tempCol, slot = 0))
            }
            if (cfg.showGpu) {
                val usageStr = s.gpuUsagePercent.valueOrNull()?.let { String.format("%.0f", it) } ?: NA
                val usageCol = if (usageStr == NA) COLOR_RTSS_NA else COLOR_RTSS_STAT
                gpuStats.add(RtssStat(usageStr, if (usageStr == NA) "" else "%", usageCol, slot = 1))

                val clkVal = s.gpuFreqMhz.valueOrNull()
                val clkStr = clkVal?.toString() ?: NA
                val clkCol = if (clkStr == NA) COLOR_RTSS_NA else COLOR_RTSS_STAT
                gpuStats.add(RtssStat(clkStr, if (clkStr == NA) "" else "MHz", clkCol, slot = 2))
            }
            if (gpuStats.isNotEmpty()) {
                out += RtssRow("GPU", COLOR_RTSS_GPU, gpuStats)
            }
            if (cfg.showGpu) {
                s.gpuMemUsedMb.valueOrNull()?.let { memMb ->
                    out += RtssRow(
                        "MEM",
                        COLOR_RTSS_GPU,
                        listOf(RtssStat(memMb.toString(), "MB", COLOR_RTSS_STAT, slot = 0))
                    )
                }
            }
        }

        val cpuWanted = cfg.showCpu || cfg.showCpuTemp
        if (cpuWanted) {
            val cpuStats = mutableListOf<RtssStat>()
            if (cfg.showCpuTemp) {
                val tempStr = s.cpuTempC.valueOrNull()?.let { String.format("%.0f", it) } ?: NA
                val tempCol = if (tempStr == NA) COLOR_RTSS_NA else COLOR_RTSS_STAT
                cpuStats.add(RtssStat(tempStr, if (tempStr == NA) "" else "°C", tempCol, slot = 0))
            }
            if (cfg.showCpu) {
                val usageStr = s.cpuTotalPercent.valueOrNull()?.let { String.format("%.0f", it) } ?: NA
                val usageCol = if (usageStr == NA) COLOR_RTSS_NA else COLOR_RTSS_STAT
                cpuStats.add(RtssStat(usageStr, if (usageStr == NA) "" else "%", usageCol, slot = 1))

                val maxFreq = s.cpuFreqMhz.valueOrNull()?.maxOrNull()
                val clkStr = maxFreq?.toString() ?: NA
                val clkCol = if (clkStr == NA) COLOR_RTSS_NA else COLOR_RTSS_STAT
                cpuStats.add(RtssStat(clkStr, if (clkStr == NA) "" else "MHz", clkCol, slot = 2))
            }
            if (cpuStats.isNotEmpty()) {
                out += RtssRow("CPU", COLOR_RTSS_CPU, cpuStats)
            }
        }

        if (cfg.showBatteryTemp) {
            val batTemp = s.batteryTempC.valueOrNull()
            if (batTemp != null) {
                val batStats = mutableListOf<RtssStat>()
                batStats.add(RtssStat(String.format("%.1f", batTemp), "°C", COLOR_RTSS_STAT, slot = 0))
                if (cfg.showBattery) {
                    s.batteryLevelPercent.valueOrNull()?.let { lvl ->
                        batStats.add(RtssStat(lvl.toString(), "%", COLOR_RTSS_STAT, slot = 1))
                    }
                }
                out += RtssRow("BAT", COLOR_RTSS_BAT, batStats)
            }
        }

        if (cfg.showRam) {
            s.ramUsedBytes.valueOrNull()?.let { bytes ->
                val ramMb = bytes / 1_048_576L
                out += RtssRow(
                    "RAM",
                    COLOR_RTSS_CPU,
                    listOf(RtssStat(ramMb.toString(), "MB", COLOR_RTSS_STAT, slot = 0))
                )
            }
        }

        if (cfg.showFps) {
            val fpsStats = mutableListOf<RtssStat>()
            val fpsStr = s.fps.valueOrNull()?.let { String.format("%.0f", it) } ?: NA
            val fpsCol = if (fpsStr == NA) COLOR_RTSS_NA else COLOR_RTSS_WHITE
            fpsStats.add(RtssStat(fpsStr, if (fpsStr == NA) "" else "FPS", fpsCol, slot = 0))

            if (cfg.showFrameTime) {
                val ftStr = s.frameTimeMs.valueOrNull()?.let { String.format("%.1f", it) } ?: NA
                val ftCol = if (ftStr == NA) COLOR_RTSS_NA else COLOR_RTSS_WHITE
                fpsStats.add(RtssStat(ftStr, if (ftStr == NA) "" else "ms", ftCol, slot = 1))
            }

            out += RtssRow("FPS", COLOR_RTSS_API, fpsStats)
        }

        return out
    }

    fun rtssLines(s: MetricsSnapshot, cfg: OverlayConfig): List<OsdLine> =
        rtss(s, cfg).map { OsdLine(it.toFormattedText(), it.labelColor) }

    // Authentic RTSS Signature Colors
    const val COLOR_RTSS_GPU = 0xFF00AA44.toInt()      // Emerald Green (GPU, MEM)
    const val COLOR_RTSS_CPU = 0xFF0099EE.toInt()      // Sky Blue / Cyan (CPU, RAM)
    const val COLOR_RTSS_API = 0xFFD89696.toInt()      // Peach / Salmon Pink (D3D11)
    const val COLOR_RTSS_BAT = 0xFFFFB300.toInt()      // Amber (BAT)
    const val COLOR_RTSS_STAT = 0xFFFF8000.toInt()     // Vibrant RTSS Orange (values & units)
    const val COLOR_RTSS_WHITE = 0xFFFFFFFF.toInt()    // Pure Crisp White (Framerate & Frametime)
    const val COLOR_RTSS_NA = 0xFF8A8A8A.toInt()       // Neutral Gray (N/A)

    // Category colors (backward-compatible aliases)
    const val COLOR_GPU = COLOR_RTSS_GPU
    const val COLOR_CPU = COLOR_RTSS_CPU
    const val COLOR_RAM = COLOR_RTSS_CPU
    const val COLOR_FPS = COLOR_RTSS_WHITE
    const val COLOR_FPS_AVG = 0xFFB0BEC5.toInt()
    const val COLOR_LOW = 0xFFEF9A9A.toInt()
    const val COLOR_NA = COLOR_RTSS_NA

    // Modern HUD color tokens
    const val COLOR_FPS_MODERN = 0xFF00E699.toInt()  // Emerald green
    const val COLOR_CPU_MODERN = 0xFF00D2FF.toInt()  // Cyan
    const val COLOR_GPU_MODERN = 0xFFB066FF.toInt()  // Violet
    const val COLOR_RAM_MODERN = 0xFF10D88C.toInt()  // Emerald
    const val COLOR_TEMP_MODERN = 0xFFFFA726.toInt() // Orange
    const val COLOR_BAT_TEMP_MODERN = 0xFFFFA726.toInt() // Warm Amber / Orange
    const val COLOR_CPU_TEMP_MODERN = 0xFFFF7043.toInt() // Coral Red

    /** Reasons for every unavailable metric, for the diagnostics screen. */
    fun unavailableReasons(s: MetricsSnapshot): Map<String, String> = buildMap {
        s.fps.unavailableReason()?.let { put("FPS", it) }
        s.cpuTotalPercent.unavailableReason()?.let { put("CPU", it) }
        s.gpuUsagePercent.unavailableReason()?.let { put("GPU usage", it) }
        s.gpuFreqMhz.unavailableReason()?.let { put("GPU frequency", it) }
        s.gpuTempC.unavailableReason()?.let { put("GPU temperature", it) }
        s.ramUsedBytes.unavailableReason()?.let { put("RAM", it) }
        s.cpuTempC.unavailableReason()?.let { put("CPU temperature", it) }
        s.batteryLevelPercent.unavailableReason()?.let { put("Battery", it) }
        s.netDownBytesPerSec.unavailableReason()?.let { put("Network", it) }
        s.foregroundPackage.unavailableReason()?.let { put("Foreground app", it) }
    }
}
