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

/**
 * User's configured preferred privileged access method.
 * Persisted in preferences so the chosen mode stays active across app restarts.
 */
enum class AccessMode {
    ROOT,
    SHIZUKU;

    val displayName: String
        get() = when (this) {
            ROOT -> "Root"
            SHIZUKU -> "Shizuku (Wireless ADB)"
        }
}
