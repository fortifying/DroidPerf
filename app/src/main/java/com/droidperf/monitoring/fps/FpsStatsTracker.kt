package com.droidperf.monitoring.fps

/**
 * Rolling statistics over the FPS monitor's per-window samples, powering the RTSS-style
 * overlay's "FPS Average" and "1% Lows" rows.
 *
 * Each sample is the average presented-frame rate over one sampling window (≈ the
 * overlay interval, default 1 s) as reported by SurfaceFlinger. Statistics therefore
 * have one-second resolution, not per-frame resolution: "1% Lows" is the average of the
 * slowest ~1% of window samples over the history below, which is the honest equivalent
 * at this sampling granularity — it is never interpolated or scaled.
 *
 * The buffer is cleared whenever the measured foreground app changes, so averages never
 * mix two applications. Nothing here allocates per frame; it is O(1) per sample.
 */
class FpsStatsTracker(private val maxSamples: Int = DEFAULT_MAX_SAMPLES) {

    private val samples = ArrayDeque<Double>(maxSamples)

    /** Drop all history (e.g. the measured app changed). */
    fun clear() = samples.clear()

    /** Record one window average. Non-positive values are ignored, never fabricated. */
    fun feed(fps: Double) {
        if (!fps.isFinite() || fps <= 0.0) return
        if (samples.size >= maxSamples) samples.removeFirst()
        samples.addLast(fps)
    }

    /** Mean of the history, or null before [MIN_FOR_AVERAGE] samples exist. */
    fun average(): Double? {
        if (samples.size < MIN_FOR_AVERAGE) return null
        var sum = 0.0
        for (v in samples) sum += v
        return sum / samples.size
    }

    /**
     * Average of the slowest ~1% of samples, or null before [MIN_FOR_LOW] samples exist.
     * At least one sample always counts, matching the "worst 1%" convention.
     */
    fun onePercentLow(): Double? {
        if (samples.size < MIN_FOR_LOW) return null
        val count = maxOf(1, (samples.size * 0.01).toInt().coerceAtLeast(1))
        val worst = samples.sorted().take(count)
        var sum = 0.0
        for (v in worst) sum += v
        return sum / count
    }

    fun sampleCount(): Int = samples.size

    private companion object {
        /** ~10 minutes of history at the default 1 s interval. */
        const val DEFAULT_MAX_SAMPLES = 600
        const val MIN_FOR_AVERAGE = 3
        const val MIN_FOR_LOW = 30
    }
}
