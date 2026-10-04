package com.droidperf.system.shell

import com.droidperf.domain.AccessLevel
import java.io.BufferedReader

/**
 * Runs a command as the app's own UID. On modern Android this reaches almost no system
 * interfaces (no SurfaceFlinger, no foreign /proc), but it is the honest baseline: when
 * it fails we report that we need Shizuku or root rather than pretending.
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
