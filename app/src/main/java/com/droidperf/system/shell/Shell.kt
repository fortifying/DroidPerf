package com.droidperf.system.shell

import com.droidperf.domain.AccessLevel

/**
 * Result of a shell command. We keep exit code and both streams so callers can tell a
 * genuine "not supported here" apart from an actual error, and never treat empty output
 * as a real zero.
 */
data class ShellResult(
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>,
) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * Executes a *fixed, app-owned* command. The command allow-list lives in
 * [SafeCommands]; nothing else may be passed here, which is what keeps this a
 * monitoring tool rather than an arbitrary code execution surface.
 */
interface Shell {
    val accessLevel: AccessLevel
    fun exec(command: String): ShellResult?
    fun isAvailable(): Boolean
}
