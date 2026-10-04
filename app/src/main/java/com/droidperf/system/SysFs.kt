package com.droidperf.system

import java.io.File

/**
 * Reads small sysfs / proc text nodes. Sysfs reads are cheap but not free, so callers
 * cache aggressively. A missing or permission-denied node returns null, never a default
 * that could be mistaken for a real reading.
 *
 * On modern Android (MIUI/HyperOS in particular) SELinux blocks most /sys and /proc
 * nodes to the app process even when the device is rooted. Callers therefore get a
 * second chance through [privilegedRead]/[privilegedList]/[privilegedExists], which
 * MetricsRepository wires to the active root or Shizuku shell; the shell path only ever
 * runs commands from [com.droidperf.system.shell.SafeCommands] on validated paths.
 */
object SysFs {

    /** Content of [path] via the privileged shell, or null when unavailable/denied. */
    @Volatile
    var privilegedRead: ((path: String) -> String?)? = null

    /** Directory entries of [path] via the privileged shell, or null when unavailable. */
    @Volatile
    var privilegedList: ((path: String) -> List<String>?)? = null

    /** Existence probe of [path] via the privileged shell, or null when unavailable. */
    @Volatile
    var privilegedExists: ((path: String) -> Boolean?)? = null

    fun readText(path: String): String? = try {
        val f = File(path)
        if (f.exists() && f.canRead()) f.readText().trim()
        else privilegedRead?.invoke(path)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    private val whitespaceRegex = Regex("\\s+")

    fun readLong(path: String): Long? = readText(path)?.toLongOrNull()
    fun readInt(path: String): Int? = readText(path)?.toIntOrNull()

    /** First parseable number in a possibly multi-token file (e.g. freq tables). */
    fun readFirstInt(path: String): Int? =
        readText(path)?.split(whitespaceRegex)?.firstNotNullOfOrNull { it.toIntOrNull() }

    fun exists(path: String): Boolean = try {
        val f = File(path)
        if (f.exists() && f.canRead()) true
        else privilegedExists?.invoke(path) ?: false
    } catch (_: Throwable) {
        false
    }

    fun listDir(path: String): List<String> = try {
        File(path).list()?.toList() ?: privilegedList?.invoke(path) ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }
}
