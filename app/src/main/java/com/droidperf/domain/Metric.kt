package com.droidperf.domain

/**
 * A single measured value plus whether it is actually available.
 *
 * The whole app is built around this type so that an unavailable metric can never be
 * silently rendered as a number. [Unavailable] carries the reason so the diagnostics
 * screen can explain *why* a metric is missing instead of inventing a value.
 */
sealed interface Metric<out T> {
    val isAvailable: Boolean

    data class Available<T>(val value: T) : Metric<T> {
        override val isAvailable: Boolean get() = true
    }

    data class Unavailable(val reason: String) : Metric<Nothing> {
        override val isAvailable: Boolean get() = false
    }
}

/** Convenience: wrap a nullable value, turning null into an explained unavailable. */
fun <T> metricOrUnavailable(value: T?, reason: String): Metric<T> =
    if (value != null) Metric.Available(value) else Metric.Unavailable(reason)

fun <T> T?.asMetric(reason: String): Metric<T> = metricOrUnavailable(this, reason)

/** The value if available, else null. Never fabricates a fallback. */
fun <T> Metric<T>.valueOrNull(): T? = (this as? Metric.Available)?.value

/** The unavailable reason if this metric is missing, else null. */
fun Metric<*>.unavailableReason(): String? = (this as? Metric.Unavailable)?.reason

/** Everything the overlay can render, each field independently available or not. */
data class MetricsSnapshot(
    // FPS / frame timing
    val fps: Metric<Double> = Metric.Unavailable("not sampled yet"),
    val frameTimeMs: Metric<Double> = Metric.Unavailable("not sampled yet"),
    val fpsAvg: Metric<Double> = Metric.Unavailable("collecting frame history"),
    val fpsOnePercentLow: Metric<Double> = Metric.Unavailable("collecting frame history"),
    val displayRefreshRateHz: Metric<Float> = Metric.Unavailable("not sampled yet"),
    val fpsSource: String = "none",

    // CPU
    val cpuTotalPercent: Metric<Double> = Metric.Unavailable("not sampled yet"),
    val perCorePercent: Metric<List<Double>> = Metric.Unavailable("not sampled yet"),
    val cpuFreqMhz: Metric<List<Int>> = Metric.Unavailable("not sampled yet"),
    val cpuGovernor: Metric<String> = Metric.Unavailable("not sampled yet"),
    val cpuTempC: Metric<Double> = Metric.Unavailable("not sampled yet"),

    // GPU
    val gpuUsagePercent: Metric<Double> = Metric.Unavailable("not sampled yet"),
    val gpuFreqMhz: Metric<Int> = Metric.Unavailable("not sampled yet"),
    val gpuTempC: Metric<Double> = Metric.Unavailable("not sampled yet"),
    val gpuMemUsedMb: Metric<Int> = Metric.Unavailable("no GPU memory counter on this device"),
    val gpuVendor: GpuVendor = GpuVendor.UNKNOWN,

    // RAM
    val ramUsedBytes: Metric<Long> = Metric.Unavailable("not sampled yet"),
    val ramTotalBytes: Metric<Long> = Metric.Unavailable("not sampled yet"),
    val ramAvailableBytes: Metric<Long> = Metric.Unavailable("not sampled yet"),
    val ramCachedBytes: Metric<Long> = Metric.Unavailable("not sampled yet"),

    // Temperature (device / skin / battery)
    val deviceTempC: Metric<Double> = Metric.Unavailable("not sampled yet"),
    val batteryTempC: Metric<Double> = Metric.Unavailable("not sampled yet"),

    // Battery
    val batteryLevelPercent: Metric<Int> = Metric.Unavailable("not sampled yet"),
    val batteryCurrentUa: Metric<Int> = Metric.Unavailable("not sampled yet"),
    val batteryVoltageMv: Metric<Int> = Metric.Unavailable("not sampled yet"),
    val batteryPowerW: Metric<Double> = Metric.Unavailable("not sampled yet"),
    val batteryCharging: Metric<Boolean> = Metric.Unavailable("not sampled yet"),

    // Network
    val netDownBytesPerSec: Metric<Long> = Metric.Unavailable("not sampled yet"),
    val netUpBytesPerSec: Metric<Long> = Metric.Unavailable("not sampled yet"),
    val netPingMs: Metric<Int> = Metric.Unavailable("not sampled yet"),

    // Display / device
    val screenWidth: Metric<Int> = Metric.Unavailable("not sampled yet"),
    val screenHeight: Metric<Int> = Metric.Unavailable("not sampled yet"),
    val densityDpi: Metric<Int> = Metric.Unavailable("not sampled yet"),

    // Foreground app
    val foregroundPackage: Metric<String> = Metric.Unavailable("not sampled yet"),

    // Meta
    val accessLevel: AccessLevel = AccessLevel.STANDARD,
    val timestampMs: Long = 0L,
)

