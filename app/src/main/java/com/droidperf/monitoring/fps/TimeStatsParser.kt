package com.droidperf.monitoring.fps

/**
 * Pure parsing for `dumpsys SurfaceFlinger --timestats -dump` output.
 *
 * Relevant shape (per layer block):
 *   layerName = 431357a SurfaceView[com.pkg/Activity](BLAST)#220671
 *   averageFPS = 62.500
 *
 * Layers with no frames since the last clear report `layerName = none`. We only trust a
 * block whose layer name contains the foreground package, preferring its SurfaceView
 * layer (games render there). The average is over the window since the previous
 * clear, which is one sampling interval — a real presented-frame average from
 * SurfaceFlinger, not a guess. An explicit zero for a named layer is a valid idle reading.
 */
object TimeStatsParser {

    data class Result(
        val fps: Double,
        val layer: String,
    )

    fun parse(output: List<String>, pkg: String): Result? {
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
                if (fps != null && fps.isFinite() && fps >= 0.0 && layer != null && layer.contains(pkg)) {
                    candidates.add(layer to fps)
                }
                currentLayer = null
                hasLayerEntry = false
            }
        }

        val chosen = candidates.firstOrNull { it.first.contains("SurfaceView") }
            ?: candidates.firstOrNull()
            ?: if (hasValidLayerSample) return Result(fps = 0.0, layer = "$pkg (no frames in sample window)") else return null
        return Result(fps = chosen.second, layer = chosen.first)
    }
}
