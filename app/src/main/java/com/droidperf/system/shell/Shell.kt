package com.droidperf.system.shell

import com.droidperf.domain.AccessLevel
import java.io.BufferedReader

data class ShellResult(
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>,
) {
    val ok: Boolean get() = exitCode == 0
}

interface Shell {
    val accessLevel: AccessLevel
    fun isAvailable(): Boolean
    fun exec(command: String): ShellResult?
}

/**
 * Runs a command as the app's own UID. Baseline unprivileged shell.
 */
class StandardShell : Shell {
    override val accessLevel = AccessLevel.STANDARD

    override fun isAvailable(): Boolean = true

    override fun exec(command: String): ShellResult? = try {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(false)
            .start()
        try {
            process.outputStream.close()
            val stdout = readAll(process.inputStream.bufferedReader())
            val stderr = readAll(process.errorStream.bufferedReader())
            val code = process.waitFor()
            ShellResult(code, stdout, stderr)
        } finally {
            process.destroy()
        }
    } catch (_: Throwable) {
        null
    }

    private fun readAll(reader: BufferedReader): List<String> =
        reader.useLines { it.toList() }
}

/**
 * Runs commands with the ADB shell identity (UID 2000) via Shizuku.
 */
class ShizukuShell : Shell {
    override val accessLevel = AccessLevel.SHIZUKU

    private var processFactory: ((String) -> ShellResult?)? = null

    fun bind(factory: (String) -> ShellResult?) {
        processFactory = factory
    }

    override fun isAvailable(): Boolean = processFactory != null

    override fun exec(command: String): ShellResult? =
        processFactory?.invoke(command)
}
