package com.droidperf.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.droidperf.di.ServiceLocator
import com.droidperf.domain.AccessMode
import com.droidperf.domain.MetricsSnapshot
import com.droidperf.settings.OverlayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Backs the main screen. Observes repository StateFlows and provides lightweight
 * UI telemetry sampling while the app is in the foreground.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = ServiceLocator.settings
    private val metrics = ServiceLocator.metrics

    val config: StateFlow<OverlayConfig> = settings.config
    val snapshot: StateFlow<MetricsSnapshot> = metrics.snapshot

    private var sampleJob: Job? = null

    init {
        refreshCapabilities()
    }

    fun refreshCapabilities() {
        viewModelScope.launch(Dispatchers.IO) {
            metrics.refreshCapabilities()
            metrics.sample(includeNetwork = false, includeLatency = false)
        }
    }

    fun startUiSampling() {
        if (sampleJob?.isActive == true) return
        sampleJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                // Only sample if the overlay service is not already sampling
                if (!config.value.enabled) {
                    metrics.sample(includeNetwork = false, includeLatency = false)
                }
                delay(1000L)
            }
        }
    }

    fun stopUiSampling() {
        sampleJob?.cancel()
        sampleJob = null
    }

    fun setOverlayEnabled(enabled: Boolean) {
        settings.update { it.copy(enabled = enabled) }
    }

    fun updateConfig(transform: (OverlayConfig) -> OverlayConfig) {
        settings.update(transform)
    }

    fun socDescription(): String = metrics.socDescription()
    fun hasUsageAccess(): Boolean = metrics.hasUsageAccess()
    fun gpuVendorName(): String = metrics.gpuModelName().ifBlank { metrics.gpuVendor().displayName }

    fun preferredAccessMode(): AccessMode = settings.getPreferredAccessMode()
    fun isRootAvailable(): Boolean = metrics.rootShell().isAvailable()
    fun isShizukuAvailable(): Boolean = ServiceLocator.shizuku.hasPermission()
    fun isPreferredModeAuthorized(): Boolean = when (preferredAccessMode()) {
        AccessMode.ROOT -> isRootAvailable()
        AccessMode.SHIZUKU -> isShizukuAvailable()
    }
    fun hasElevatedAccess(): Boolean = isPreferredModeAuthorized() || isRootAvailable() || isShizukuAvailable()
}
