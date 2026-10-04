package com.droidperf.domain

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
