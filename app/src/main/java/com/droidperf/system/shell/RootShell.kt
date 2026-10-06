package com.droidperf.system.shell

import com.droidperf.domain.AccessLevel
import com.topjohnwu.superuser.Shell as LibsuShell

/**
 * Root shell supporting KernelSU, APatch, Magisk, and standard su binaries.
 * Uses libsu backed by persistent root process with a direct process execution fallback.
 */
class RootShell : Shell {

    companion object {
        fun hasSuBinary(): Boolean {
            val paths = arrayOf(
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/system/sd/xbin/su",
                "/system/bin/failsafe/su",
                "/data/local/xbin/su",
                "/data/local/bin/su",
                "/data/local/su",
                "/data/adb/ksu/bin/su",
                "/data/adb/ap/bin/su",
                "/data/adb/magisk/su"
            )
            if (paths.any { java.io.File(it).exists() }) return true
            val pathEnv = System.getenv("PATH")
            return pathEnv?.split(":")?.any { java.io.File(it, "su").exists() } == true
        }

        fun getRootMethod(): String {
            if (!hasSuBinary()) return "None"
            return try {
                val p = ProcessBuilder("su", "-v").redirectErrorStream(true).start()
                val line = p.inputStream.bufferedReader().readLine()?.trim() ?: ""
                p.waitFor()
                when {
                    line.contains("KernelSU", ignoreCase = true) -> "KernelSU ($line)"
                    line.contains("APatch", ignoreCase = true) -> "APatch ($line)"
                    line.contains("MAGISK", ignoreCase = true) -> "Magisk ($line)"
                    line.isNotEmpty() -> "Superuser ($line)"
                    else -> "su binary detected"
                }
            } catch (_: Throwable) {
                "su binary detected"
            }
        }
    }

    override val accessLevel = AccessLevel.ROOT

    @Volatile
    private var available: Boolean? = null

    override fun isAvailable(): Boolean {
        available?.let { return it }
        if (!hasSuBinary()) {
            available = false
            return false
        }
        val cached = LibsuShell.getCachedShell()
        if (cached != null && cached.isRoot) {
            available = true
            return true
        }
        val isGranted = try {
            LibsuShell.isAppGrantedRoot()
        } catch (_: Throwable) {
            null
        }
        if (isGranted == true) {
            available = true
            return true
        }
        return testRoot()
    }

    fun requestRoot(): Boolean {
        available = null
        val libsuResult = try {
            LibsuShell.getShell().isRoot
        } catch (_: Throwable) {
            false
        }
        if (libsuResult) {
            available = true
            return true
        }
        val directResult = testRoot()
        available = directResult
        return directResult
    }

    fun testRoot(): Boolean {
        if (!hasSuBinary()) {
            available = false
            return false
        }
        return try {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val output = p.inputStream.bufferedReader().use { it.readText() }
            val exitCode = p.waitFor()
            val isRoot = exitCode == 0 && output.contains("uid=0")
            available = isRoot
            if (isRoot) {
                try {
                    if (LibsuShell.getCachedShell() == null) {
                        LibsuShell.getShell()
                    }
                } catch (_: Throwable) {}
            }
            isRoot
        } catch (_: Throwable) {
            available = false
            false
        }
    }

    fun refresh(): Boolean {
        available = null
        return isAvailable()
    }

    override fun exec(command: String): ShellResult? {
        if (!isAvailable()) return null

        val cached = LibsuShell.getCachedShell()
        if (cached != null && cached.isRoot) {
            try {
                val out = mutableListOf<String>()
                val err = mutableListOf<String>()
                val code = LibsuShell.cmd(command)
                    .to(out, err)
                    .exec()
                    .code
                return ShellResult(code, out, err)
            } catch (_: Throwable) {}
        }

        return try {
            val p = ProcessBuilder("su", "-c", command).start()
            val out = p.inputStream.bufferedReader().readLines()
            val err = p.errorStream.bufferedReader().readLines()
            val code = p.waitFor()
            ShellResult(code, out, err)
        } catch (_: Throwable) {
            null
        }
    }
}
