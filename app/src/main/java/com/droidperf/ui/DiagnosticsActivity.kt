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
 * Diagnostics screen:
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

        bindDeviceIdentity()
        refresh()
    }

    private fun bindDeviceIdentity() {
        val metrics = ServiceLocator.metrics
        binding.diagAndroidVersion.text = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val mfg = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        binding.diagDevice.text = if (model.startsWith(mfg, ignoreCase = true)) model else "$mfg $model"
        binding.diagSoc.text = metrics.socDescription().ifBlank {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MODEL.isNotBlank()) {
                "${Build.SOC_MODEL} (${Build.HARDWARE})"
            } else {
                Build.HARDWARE.ifBlank { Build.BOARD.ifBlank { "Generic SoC" } }
            }
        }
        binding.diagGpu.text = metrics.gpuModelName()
    }

    private fun refresh() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
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
        bindDeviceIdentity()
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
        val gpuLabel = metrics.gpuVendor().displayName.takeIf { it != "Unknown" }
            ?: metrics.gpuModelName().takeIf { it != "Unknown" }
            ?: "GPU"
        binding.diagGpuSource.text = "$gpuLabel (perf)"
        binding.diagRamSource.text = "/proc/meminfo"
        binding.diagSamplingInterval.text = "${settings.sampleIntervalMs / 1000.0}s"
    }
}
