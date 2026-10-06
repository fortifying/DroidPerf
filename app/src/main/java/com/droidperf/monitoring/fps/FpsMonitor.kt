package com.droidperf.monitoring.fps

import android.os.SystemClock
import com.droidperf.domain.Metric
import com.droidperf.system.shell.SafeCommands
import com.droidperf.system.shell.Shell

/**
 * Measures the *target application's* rendered FPS, not our overlay's.
 *
 * Method, in priority order:
 *
 *  1. SurfaceFlinger frame latency for the foreground app's layer
 *     (`dumpsys SurfaceFlinger --latency <layer>`). This reports the actual VSYNC
 *     present timestamps of that app's buffer queue, which is the real frame rate the
 *     game is producing. Requires shell/root. Note: on Android 14+ builds (including
 *     HyperOS) SurfaceFlinger no longer reports frames through this interface, so it
 *     only works on older devices.
 *
 *  2. SurfaceFlinger timestats (`--timestats -enable`, then `-dump` + `-clear` each
 *     sample, `-disable` on shutdown). This is the working per-layer FPS source on
 *     Android 14+/HyperOS. The average is over the window since the previous clear,
 *     i.e. roughly one sampling interval.
 *
 *  3. gfxinfo framestats for the package (`dumpsys gfxinfo <pkg> framestats`), also
 *     shell/root. Only covers HWUI rendering, so it helps for non-game apps; games
 *     rendering into a SurfaceView report nothing here.
 *
 * Choreographer is deliberately NOT used for the game: our callbacks only ever describe
 * our own process. It is only used for the overlay's own FPS, reported separately.
 */
class FpsMonitor(private val shellProvider: () -> Shell?) {

    /** Whether we have switched the device's timestats collection on. */
    @Volatile
    private var timestatsActive = false

    /**
     * Wall clock of the last timestats clear, so a reading is never averaged over a
     * stale window (e.g. the first sample after enable, or after the overlay was
     * paused): SurfaceFlinger averages since the last clear, so a long gap would make
     * one slow number masquerade as the current frame rate.
     */
    @Volatile
    private var lastClearElapsedMs = 0L

    /** Resolve the active render SurfaceFlinger layer that belongs to [pkg], or topmost window if [pkg] is null. */
    private fun findLayer(shell: Shell, pkg: String?): String? {
        val res = shell.exec(SafeCommands.SF_LAYER_LIST) ?: return null
        if (!res.ok) return null
        val lines = res.stdout.map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    !line.contains("Background for") &&
                    !line.contains("ActivityRecord") &&
                    !line.contains("Dim layer") &&
                    !line.contains("leash") &&
                    !line.contains("SnapshotStartingWindow") &&
                    !line.contains("Overlay") &&
                    !line.contains("Toast") &&
                    !line.contains("InputMethod") &&
                    !line.contains("NavigationBar") &&
                    !line.contains("StatusBar")
            }
        val candidates = if (pkg != null) {
            val matching = lines.filter { it.contains(pkg) }
            if (matching.isNotEmpty()) matching else lines
        } else {
            lines
        }
        val blastSurfaceView = candidates.lastOrNull { it.contains("SurfaceView") && it.contains("BLAST") }
        val surfaceView = candidates.lastOrNull { it.contains("SurfaceView") }
        val blast = candidates.lastOrNull { it.contains("BLAST") || it.contains("BBQ") }
        val appWindow = candidates.lastOrNull { it.contains("/") }
        val chosen = blastSurfaceView ?: surfaceView ?: blast ?: appWindow ?: candidates.lastOrNull()
        return chosen?.takeIf { SafeCommands.isSafeLayerName(it) }
    }

    data class Fps(
        val fps: Metric<Double>,
        val frameTimeMs: Metric<Double>,
        val source: String,
    )

    fun sample(pkg: String?): Fps {
        val shell = shellProvider()
            ?: return Fps(
                Metric.Unavailable("FPS needs Shizuku or root to read frame statistics"),
                Metric.Unavailable("FPS needs Shizuku or root to read frame statistics"),
                "none",
            )

        val targetPkg = pkg?.takeIf { SafeCommands.isSafePackageName(it) }

        // SurfaceFlinger latency for the resolved layer.
        val layer = findLayer(shell, targetPkg)

        if (layer != null) {
            val res = shell.exec(SafeCommands.layerLatency(layer))
            if (res != null && res.ok) {
                FrameLatencyParser.parse(res.stdout)?.let { r ->
                    return Fps(
                        Metric.Available(round1(r.fps)),
                        if (r.fps > 0.0) Metric.Available(round1(r.frameTimeMs))
                        else Metric.Unavailable("no frames in the current sample window"),
                        "SurfaceFlinger layer: $layer",
                    )
                }
            }
        }

        // SurfaceFlinger timestats for Android 14+ / HyperOS.
        timestatsFps(shell, targetPkg)?.let { return it }

        // gfxinfo framestats fallback.
        if (targetPkg != null) {
            val gfx = shell.exec(SafeCommands.gfxInfoFramestats(targetPkg))
            if (gfx != null && gfx.ok) {
                FrameStatsParser.parse(gfx.stdout)?.let { r ->
                    return Fps(
                        Metric.Available(round1(r.fps)),
                        Metric.Available(round1(r.frameTimeMs)),
                        "gfxinfo framestats: $targetPkg",
                    )
                }
            }
        }

        return Fps(
            Metric.Unavailable("no frames in the current sample window"),
            Metric.Unavailable("no frames in the current sample window"),
            "none",
        )
    }

    /**
     * Per-layer presented-frame average from SurfaceFlinger timestats. Collection is
     * enabled once on first use; every sample dumps then clears, so each reading covers
     * exactly the window since the previous sample (≈ one interval, real-time). When
     * that window is missing or stale (first sample, or the overlay was paused), we
     * start a fresh window and return null for this tick instead of reporting an
     * average accumulated over an unknown period.
     */
    private fun timestatsFps(shell: Shell, pkg: String?): Fps? {
        val now = SystemClock.elapsedRealtime()
        val stale = lastClearElapsedMs == 0L || now - lastClearElapsedMs > STALE_WINDOW_MS

        if (!timestatsActive) {
            val enable = shell.exec(SafeCommands.SF_TIMESTATS_ENABLE) ?: return null
            // Verify the interface exists before trusting later dumps.
            if (!enable.ok && enable.stderr.isEmpty()) return null
            timestatsActive = true
        }

        if (stale) {
            shell.exec(SafeCommands.SF_TIMESTATS_CLEAR)
            lastClearElapsedMs = now
            return null
        }

        val dump = shell.exec(SafeCommands.SF_TIMESTATS_DUMP) ?: return null
        shell.exec(SafeCommands.SF_TIMESTATS_CLEAR)
        lastClearElapsedMs = now
        if (!dump.ok) return null
        val r = TimeStatsParser.parse(dump.stdout, pkg) ?: return null
        return Fps(
            Metric.Available(round1(r.fps)),
            if (r.fps > 0.0) Metric.Available(round1(1000.0 / r.fps))
            else Metric.Unavailable("no frames in the current sample window"),
            if (r.fps > 0.0) "SurfaceFlinger timestats"
            else "SurfaceFlinger timestats: no frames in the current sample window",
        )
    }

    /** Stop frame-stat collection on the device. Safe to call multiple times. */
    fun shutdown() {
        if (!timestatsActive) return
        timestatsActive = false
        lastClearElapsedMs = 0L
        val shell = shellProvider() ?: return
        shell.exec(SafeCommands.SF_TIMESTATS_CLEAR)
        shell.exec(SafeCommands.SF_TIMESTATS_DISABLE)
    }

    private companion object {
        /** A window longer than this is discarded rather than averaged over. */
        const val STALE_WINDOW_MS = 5_000L
    }

    private fun round1(v: Double) = kotlin.math.round(v * 10.0) / 10.0
}

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

