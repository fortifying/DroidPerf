package com.droidperf.monitoring.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.WindowManager
import com.droidperf.domain.Metric

/**
 * Real display geometry and refresh rate.
 *
 * On Android 11+ we use WindowManager.getCurrentWindowMetrics, which reports the
 * actual current bounds (correct for foldables, multi-window, and per-display density).
 * Below that we use the still-supported getRealMetrics. Refresh rate comes from the
 * active Display.Mode, which is the actual rate, not a hardcoded 60.
 */
class DisplayMonitor(private val context: Context) {

    data class Display(
        val width: Metric<Int>,
        val height: Metric<Int>,
        val densityDpi: Metric<Int>,
        val refreshRateHz: Metric<Float>,
    )

    fun sample(): Display {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

        var w: Int? = null
        var h: Int? = null
        var dpi: Int? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            val bounds = wm.currentWindowMetrics.bounds
            w = bounds.width()
            h = bounds.height()
            dpi = context.resources.configuration.densityDpi
        } else if (wm != null) {
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            w = metrics.widthPixels
            h = metrics.heightPixels
            dpi = metrics.densityDpi
        }

        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val refresh = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            ?.let { display ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    display.mode.refreshRate
                } else {
                    @Suppress("DEPRECATION")
                    display.refreshRate
                }
            }

        return Display(
            width = w?.let { Metric.Available(it) } ?: Metric.Unavailable("display width unavailable"),
            height = h?.let { Metric.Available(it) } ?: Metric.Unavailable("display height unavailable"),
            densityDpi = dpi?.let { Metric.Available(it) } ?: Metric.Unavailable("density unavailable"),
            refreshRateHz = refresh?.let { Metric.Available(it) } ?: Metric.Unavailable("refresh rate unavailable"),
        )
    }
}
