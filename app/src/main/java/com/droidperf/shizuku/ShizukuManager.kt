package com.droidperf.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import com.droidperf.BuildConfig
import com.droidperf.system.shell.ShellResult
import com.droidperf.system.shell.ShizukuShell
import rikka.shizuku.Shizuku

/**
 * Owns the Shizuku lifecycle. All Shizuku calls are confined here so the rest of the app
 * never has to know whether Shizuku exists; it just asks for a [ShizukuShell].
 *
 * We use the supported `UserService` mechanism: Shizuku starts [PerfUserService] in a
 * separate process running as the shell user (uid 2000), and we call it over AIDL. This
 * replaces the deprecated/removed `Shizuku.newProcess` API.
 *
 * Shizuku is strictly optional. When it is missing or unauthorized, the shell stays
 * unavailable and every consumer falls back to Standard access.
 */
class ShizukuManager(
    private val context: Context,
    private val shizukuShell: ShizukuShell,
) {

    private val requestCode = 4001

    @Volatile
    private var boundService: IPerfShell? = null

    @Volatile
    private var connecting = false

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, PerfUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            connecting = false
            if (binder != null && binder.pingBinder()) {
                boundService = IPerfShell.Stub.asInterface(binder)
                bindShell()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            connecting = false
        }
    }

    init {
        // Sticky listener: if Shizuku's binder is already alive we bind immediately,
        // otherwise we bind as soon as it comes up. No polling.
        try {
            Shizuku.addBinderReceivedListenerSticky {
                if (hasPermission()) bindUserService()
            }
            Shizuku.addBinderDeadListener {
                boundService = null
                connecting = false
            }
        } catch (_: Throwable) {
            // Shizuku not installed; standard access remains in force.
        }
    }

    /** True when the Shizuku app is installed and its binder is alive. */
    fun isBinderAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun hasPermission(): Boolean = try {
        isBinderAvailable() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /**
     * Ask the user to grant Shizuku access. On success we bind a process factory that
     * runs commands through Shizuku's shell identity.
     */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        if (!isBinderAvailable()) {
            onResult(false)
            return
        }
        if (hasPermission()) {
            bindUserService()
            onResult(true)
            return
        }
        try {
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(req: Int, grantResult: Int) {
                    if (req == requestCode) {
                        try {
                            Shizuku.removeRequestPermissionResultListener(this)
                        } catch (_: Throwable) {}
                        val granted = grantResult == PackageManager.PERMISSION_GRANTED
                        if (granted) bindUserService()
                        onResult(granted)
                    }
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(requestCode)
        } catch (_: Throwable) {
            onResult(false)
        }
    }

    /** Bind the user service if we already hold permission (called on startup). */
    fun bindIfPermitted() {
        if (hasPermission()) bindUserService()
    }

    private fun bindUserService() {
        if (boundService != null || connecting) return
        connecting = true
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (_: Throwable) {
            connecting = false
        }
    }

    private fun bindShell() {
        shizukuShell.bind { command -> runViaUserService(command) }
    }

    /**
     * Execute one allow-listed command through the shell-privileged user service.
     * The service returns [exitCode, stdout, stderr].
     */
    private fun runViaUserService(command: String): ShellResult? {
        val service = boundService ?: return null
        return try {
            val parts = service.exec(command)
            if (parts.size < 3) return null
            val code = parts[0].toIntOrNull() ?: return null
            ShellResult(
                exitCode = code,
                stdout = parts[1].lines().filter { it.isNotEmpty() },
                stderr = parts[2].lines().filter { it.isNotEmpty() },
            )
        } catch (_: RemoteException) {
            null
        } catch (_: Throwable) {
            null
        }
    }
}
