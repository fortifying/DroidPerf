package com.droidperf.monitoring.fps

/**
 * Pure parsing + math for `dumpsys SurfaceFlinger --latency <layer>` output.
 */
object FrameLatencyParser {

    private const val NO_FRAME = 9223372036854775807L
    private val WHITESPACE_REGEX = Regex("\\s+")

    data class Result(
        val fps: Double,
        val frameTimeMs: Double,
        val frameCount: Int,
        val refreshRateHz: Double?,
    )

    fun parse(output: List<String>): Result? {
        if (output.isEmpty()) return null

        val refreshPeriodNs = output.firstOrNull()?.trim()?.toLongOrNull()

        val presentTimes = ArrayList<Long>(output.size)
        var validFrameRows = 0
        for (line in output.drop(1)) {
            val parts = line.trim().split(WHITESPACE_REGEX)
            if (parts.size < 3) continue
            val actual = parts[1].toLongOrNull() ?: continue
            validFrameRows++
            if (actual == 0L || actual == NO_FRAME) continue
            presentTimes.add(actual)
        }

        val refresh = refreshPeriodNs
            ?.takeIf { it in 1..100_000_000L }
            ?.let { 1_000_000_000.0 / it }

        if (presentTimes.isEmpty()) {
            if (refresh != null && validFrameRows > 0) {
                return Result(0.0, 0.0, 0, refresh)
            }
            return null
        }

        if (presentTimes.size < 2) return null

        val first = presentTimes.min()
        val last = presentTimes.max()
        val spanNs = last - first
        if (spanNs <= 0) return null

        val spanSec = spanNs / 1_000_000_000.0
        val fps = (presentTimes.size - 1) / spanSec
        if (!fps.isFinite() || fps <= 0) return null

        val frameTimeMs = spanNs.toDouble() / 1_000_000.0 / (presentTimes.size - 1)

        return Result(fps, frameTimeMs, presentTimes.size, refresh)
    }
}

/**
 * Parses `dumpsys gfxinfo <pkg> framestats`.
 */
object FrameStatsParser {

    data class Result(val fps: Double, val frameTimeMs: Double, val frameCount: Int)

    fun parse(lines: List<String>): Result? {
        val idx = lines.indexOfFirst { it.trim().startsWith("FRAMESTATS") }
        if (idx < 0) return null

        val vsyncNs = ArrayList<Long>(128)
        for (i in idx + 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            if (!line.startsWith("0,")) continue
            val parts = line.split(",")
            if (parts.size < 3) continue
            if (parts[1] != "INTENDED_VSYNC") continue
            val ts = parts[2].toLongOrNull() ?: continue
            if (ts > 0) vsyncNs.add(ts)
        }

        if (vsyncNs.size < 2) return null
        vsyncNs.sort()
        val spanNs = vsyncNs.last() - vsyncNs.first()
        if (spanNs <= 0) return null

        val fps = (vsyncNs.size - 1) / (spanNs / 1_000_000_000.0)
        if (!fps.isFinite() || fps <= 0) return null
        val frameMs = spanNs.toDouble() / 1_000_000.0 / (vsyncNs.size - 1)
        return Result(fps, frameMs, vsyncNs.size)
    }
}

/**
 * Pure parsing for `dumpsys SurfaceFlinger --timestats -dump` output.
 */
object TimeStatsParser {

    data class Result(
        val fps: Double,
        val layer: String,
    )

    fun parse(output: List<String>, pkg: String?): Result? {
        var currentLayer: String? = null
        var hasLayerEntry = false
        var hasValidLayerSample = false
        val candidates = ArrayList<Pair<String, Double>>()

        for (raw in output) {
            val line = raw.trim()
            if (line.startsWith("layerName")) {
                hasLayerEntry = true
                currentLayer = line.substringAfter('=', "").trim().takeIf { it != "none" }
            } else if (line.startsWith("averageFPS")) {
                val fps = line.substringAfter('=', "").trim().toDoubleOrNull()
                val layer = currentLayer
                if (hasLayerEntry && layer != null && fps != null && fps.isFinite() && fps >= 0.0) {
                    hasValidLayerSample = true
                }
                if (fps != null && fps.isFinite() && fps >= 0.0 && layer != null) {
                    if (pkg == null || layer.contains(pkg)) {
                        candidates.add(layer to fps)
                    }
                }
                currentLayer = null
                hasLayerEntry = false
            }
        }

        val filtered = if (pkg == null) {
            candidates.filter { (layer, _) ->
                !layer.contains("Wallpaper") &&
                !layer.contains("StatusBar") &&
                !layer.contains("NavigationBar") &&
                !layer.contains("Overlay")
            }
        } else candidates

        val chosen = filtered.firstOrNull { it.first.contains("SurfaceView") }
            ?: filtered.maxByOrNull { it.second }

        if (chosen != null) {
            return Result(fps = chosen.second, layer = chosen.first)
        }
        return if (hasValidLayerSample) {
            Result(fps = 0.0, layer = "${pkg ?: "active"} (no frames in sample window)")
        } else {
            null
        }
    }
}
