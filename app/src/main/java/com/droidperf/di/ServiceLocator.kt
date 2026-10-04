package com.droidperf.di

import android.content.Context
import com.droidperf.monitoring.MetricsRepository
import com.droidperf.settings.SettingsRepository
import com.droidperf.shizuku.ShizukuManager

/**
 * Tiny manual dependency graph. We avoid a DI framework because the graph is small and
 * a framework would add startup cost to a process whose whole purpose is low overhead.
 */
object ServiceLocator {

    private lateinit var appContext: Context

    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }
    val metrics: MetricsRepository by lazy { MetricsRepository(appContext) }
    val shizuku: ShizukuManager by lazy {
        ShizukuManager(appContext, metrics.shizukuShell())
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
