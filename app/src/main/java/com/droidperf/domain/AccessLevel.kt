package com.droidperf.domain

/**
 * Which privilege level a data source runs at. The app always prefers the lowest
 * level that can actually deliver a metric, and only escalates when a metric is
 * genuinely unreachable otherwise.
 */
enum class AccessLevel {
    STANDARD,
    SHIZUKU,
    ROOT;

    val label: String
        get() = when (this) {
            STANDARD -> "Standard"
            SHIZUKU -> "Shizuku"
            ROOT -> "Root"
        }
}
