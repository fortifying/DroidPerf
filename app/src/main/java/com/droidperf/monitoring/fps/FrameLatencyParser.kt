package com.droidperf.monitoring.fps

/**
 * Pure parsing + math for `dumpsys SurfaceFlinger --latency <layer>` output.
 *
 * Output shape:
 *   line 0: refresh period in nanoseconds
 *   line 1..N: three space separated nanosecond timestamps per frame:
 *     [0] desired present time
 *     [1] actual present time
 *     [2] frame ready time
 *
 * A frame is "presented" only when [1] is a real timestamp. The sentinel
 * 9223372036854775807 (Long.MAX_VALUE) and 0 mean "no frame", so we must never count
 * them. FPS is then real presented frames over the real elapsed wall time between the
 * first and last presented frame, which is why it reflects the target app and not us.
 * A valid sample window containing only no-frame entries is a real 0 FPS reading; an
 * empty or malformed response remains unavailable.
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

        // Collect actual present timestamps from all rows. Count parseable rows separately
        // so an explicitly empty frame window can be distinguished from bad output.
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
            // Only report zero when SurfaceFlinger gave us a valid refresh period and at
            // least one well-formed frame row. A header-only/unsupported response is N/A.
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
