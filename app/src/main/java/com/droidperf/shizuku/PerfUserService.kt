package com.droidperf.shizuku

import android.content.Context
import androidx.annotation.Keep
import com.droidperf.system.shell.SafeCommands

/**
 * The process that Shizuku starts with shell (uid 2000) privilege. It is created by the
 * Shizuku server, not by us, so it must have a public no-arg constructor (and may
 * optionally take a Context, supported from Shizuku API v13).
 *
 * Defense in depth: even though only our own app can bind this, the service re-validates
 * every command against [SafeCommands] before running it, so a compromised client could
 * still not turn this into arbitrary shell execution.
 */
class PerfUserService : IPerfShell.Stub {

    /** Required by Shizuku. */
    @Suppress("unused")
    constructor() : super()

    /** Optional Context constructor, supported from Shizuku API v13. Keep it for ProGuard. */
    @Keep
    @Suppress("unused", "UNUSED_PARAMETER")
    constructor(context: Context) : super()

    override fun destroy() {
        // Shizuku calls this to tear the process down.
        System.exit(0)
    }

    override fun exit() {
        destroy()
    }

    override fun exec(command: String): Array<String> {
        if (!SafeCommands.isAllowedCommand(command)) {
            return arrayOf("-1", "", "command rejected: not in allow-list")
        }
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            arrayOf(code.toString(), stdout, stderr)
        } catch (t: Throwable) {
            arrayOf("-1", "", t.message ?: "exec failed")
        }
    }
}
