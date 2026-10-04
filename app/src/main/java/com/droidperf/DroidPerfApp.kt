package com.droidperf

import android.app.Application
import com.droidperf.di.ServiceLocator
import com.droidperf.system.shell.RootShell
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DroidPerfApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        runCatching {
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(10)
            )
        }
        runCatching { ServiceLocator.shizuku.bindIfPermitted() }
        if (RootShell.hasSuBinary()) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { ServiceLocator.metrics.rootShell().isAvailable() }
            }
        }
    }
}
