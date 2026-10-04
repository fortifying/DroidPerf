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

    /** Resolve the SurfaceFlinger layer that belongs to [pkg]. */
    private fun findLayer(shell: Shell, pkg: String): String? {
        val res = shell.exec(SafeCommands.SF_LAYER_LIST) ?: return null
        if (!res.ok) return null
        // Layer names look like "SurfaceView[com.pkg/Activity]#0" or, on Android 12+,
        // "SurfaceView[com.pkg/Activity]#0(BLAST)". Prefer a SurfaceView layer (games
        // render there), else any layer for the package. Lines are trimmed because some
        // shells leave a trailing CR on every line.
        val candidates = res.stdout.map { it.trim() }.filter { it.contains(pkg) }
        val surfaceView = candidates.firstOrNull { it.contains("SurfaceView") }
        val chosen = surfaceView ?: candidates.firstOrNull()
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

        if (pkg == null || !SafeCommands.isSafePackageName(pkg)) {
            return Fps(
                Metric.Unavailable("no valid foreground package to measure"),
                Metric.Unavailable("no valid foreground package to measure"),
                "none",
            )
        }

        // 1. SurfaceFlinger latency for the resolved layer.
        findLayer(shell, pkg)?.let { layer ->
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

        // 2. SurfaceFlinger timestats (the working source on Android 14+/HyperOS).
        timestatsFps(shell, pkg)?.let { return it }

        // 3. gfxinfo framestats fallback.
        val gfx = shell.exec(SafeCommands.gfxInfoFramestats(pkg))
        if (gfx != null && gfx.ok) {
            FrameStatsParser.parse(gfx.stdout)?.let { r ->
                return Fps(
                    Metric.Available(round1(r.fps)),
                    Metric.Available(round1(r.frameTimeMs)),
                    "gfxinfo framestats: $pkg",
                )
            }
        }

        return Fps(
            Metric.Unavailable("frame statistics not readable for $pkg on this device/access level"),
            Metric.Unavailable("frame statistics not readable for $pkg on this device/access level"),
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
    private fun timestatsFps(shell: Shell, pkg: String): Fps? {
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
