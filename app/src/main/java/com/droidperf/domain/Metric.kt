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
