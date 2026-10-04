package com.droidperf.monitoring.fps

/**
 * Parses `dumpsys gfxinfo <pkg> framestats`. The FRAMESTATS section is CSV-ish:
 *   Window: ...
 *   FRAMESTATS,...
 *   0,INTENDED_VSYNC,1650000000000,...
 *   0,FRAME_COMPLETED,16500000016700000,...
 *
 * We take intended VSYNC timestamps of frame index 0 rows, which mark each frame's start,
 * then compute FPS and frame time from the real deltas. If the section is absent (the app
 * has no hardware-accelerated drawing, or stats were reset) we return null.
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
            // Rows are "frameIndex,FLAG,timestampNs,...". We only want the start-of-frame
            // marker INTENDED_VSYNC; counting FRAME_COMPLETED too would double the samples
            // and inflate the frame count.
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
