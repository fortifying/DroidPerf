package com.droidperf.ui

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.droidperf.R
import com.droidperf.databinding.ActivityDiagnosticsBinding
import com.droidperf.di.ServiceLocator
import com.droidperf.domain.AccessLevel
import com.droidperf.domain.valueOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Diagnostics screen matching screen 5 in concep.png:
 * SYSTEM info, ACCESS status, and MONITORING sources with live telemetry.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        binding.btnDiagBack.setOnClickListener { finish() }

        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                ServiceLocator.metrics.refreshCapabilities()
                ServiceLocator.metrics.sample(
                    includeNetwork = ServiceLocator.settings.current().networkEnabled,
                    includeLatency = false,
                )
            }
            render()
        }
    }

    private fun render() {
        val snap = ServiceLocator.metrics.snapshot.value
        val metrics = ServiceLocator.metrics
        val settings = ServiceLocator.settings.current()

        // SYSTEM
        binding.diagAndroidVersion.text = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        binding.diagDevice.text = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        binding.diagSoc.text = metrics.socDescription().ifBlank { "Qualcomm Snapdragon" }
        binding.diagGpu.text = metrics.gpuVendor().displayName
        val ramTotal = snap.ramTotalBytes.valueOrNull()
        binding.diagRam.text = if (ramTotal != null) {
            "%.0f GB".format(ramTotal / 1_073_741_824.0)
        } else "N/A"

        // ACCESS
        val isRooted = snap.accessLevel == AccessLevel.ROOT
        binding.diagStatusRoot.text = if (isRooted) "Granted" else "Not available"
        binding.diagStatusRoot.setTextColor(getColor(if (isRooted) R.color.accent_green else R.color.on_surface_muted))
        binding.diagIconRoot.visibility = if (isRooted) View.VISIBLE else View.GONE

        val shizukuOk = ServiceLocator.shizuku.hasPermission()
        binding.diagStatusShizuku.text = if (shizukuOk) "Granted" else "Not required"
        binding.diagStatusShizuku.setTextColor(getColor(if (shizukuOk) R.color.accent_green else R.color.on_surface_muted))

        val overlayOk = Permissions.canDrawOverlays(this)
        binding.diagStatusOverlay.text = if (overlayOk) "Granted" else "Missing"
        binding.diagStatusOverlay.setTextColor(getColor(if (overlayOk) R.color.accent_green else R.color.missing))
        binding.diagIconOverlay.visibility = if (overlayOk) View.VISIBLE else View.GONE

        val usageOk = metrics.hasUsageAccess()
        binding.diagStatusUsage.text = if (usageOk) "Granted" else "Missing"
        binding.diagStatusUsage.setTextColor(getColor(if (usageOk) R.color.accent_green else R.color.missing))
        binding.diagIconUsage.visibility = if (usageOk) View.VISIBLE else View.GONE

        // MONITORING
        binding.diagFpsSource.text = snap.fpsSource.ifBlank { "SurfaceFlinger" }
        binding.diagCpuSource.text = "/proc/stat"
        binding.diagGpuSource.text = "${metrics.gpuVendor().displayName} (perf)"
        binding.diagRamSource.text = "/proc/meminfo"
        binding.diagSamplingInterval.text = "${settings.sampleIntervalMs / 1000.0}s"
    }
}
