package com.droidperf.system.shell

import com.droidperf.domain.AccessLevel

/**
 * Shizuku shell: runs commands with the ADB shell identity (uid 2000). This is the
 * sweet spot for a non-rooted device, since shell can read SurfaceFlinger layer stats
 * and dumpsys activity that a normal app cannot.
 *
 * We use Shizuku's process API rather than binding a UserService so the whole thing
 * stays dependency-light. All commands still come from [SafeCommands] only.
 */
class ShizukuShell : Shell {
    override val accessLevel = AccessLevel.SHIZUKU

    private var processFactory: ((String) -> ShellResult?)? = null

    /**
     * Injected by the Shizuku manager once permission is granted. Kept as a lambda so
     * this class carries no hard compile-time dependency on Shizuku internals.
     */
    fun bind(factory: (String) -> ShellResult?) {
        processFactory = factory
    }

    override fun isAvailable(): Boolean = processFactory != null

    override fun exec(command: String): ShellResult? =
        processFactory?.invoke(command)
}
