package com.droidperf.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.droidperf.databinding.ActivityOverlayPreviewBinding
import com.droidperf.di.ServiceLocator
import com.droidperf.domain.valueOrNull
import com.droidperf.settings.OverlayPosition

class OverlayPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOverlayPreviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOverlayPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        binding.btnPreviewBack.setOnClickListener { finish() }

        val cfg = ServiceLocator.settings.current()

        // Position the simulated HUD preview according to chosen position
        val params = binding.previewHudContainer.layoutParams as ConstraintLayout.LayoutParams
        when (cfg.position) {
            OverlayPosition.TOP_LEFT -> {
                params.topToBottom = binding.previewTopBar.id
                params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                params.endToEnd = ConstraintLayout.LayoutParams.UNSET
                params.topMargin = 40
                params.marginStart = 40
            }
            OverlayPosition.TOP_CENTER -> {
                params.topToBottom = binding.previewTopBar.id
                params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                params.topMargin = 40
            }
            OverlayPosition.TOP_RIGHT -> {
                params.topToBottom = binding.previewTopBar.id
                params.startToStart = ConstraintLayout.LayoutParams.UNSET
                params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                params.topMargin = 40
                params.marginEnd = 40
            }
            OverlayPosition.CENTER_LEFT -> {
                params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                params.endToEnd = ConstraintLayout.LayoutParams.UNSET
                params.marginStart = 40
            }
            OverlayPosition.CENTER -> {
                params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            }
            OverlayPosition.CENTER_RIGHT -> {
                params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                params.startToStart = ConstraintLayout.LayoutParams.UNSET
                params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                params.marginEnd = 40
            }
            OverlayPosition.BOTTOM_LEFT -> {
                params.bottomToTop = binding.previewBottomCard.id
                params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                params.endToEnd = ConstraintLayout.LayoutParams.UNSET
                params.bottomMargin = 40
                params.marginStart = 40
            }
            OverlayPosition.BOTTOM_CENTER -> {
                params.bottomToTop = binding.previewBottomCard.id
                params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                params.bottomMargin = 40
            }
            OverlayPosition.BOTTOM_RIGHT -> {
                params.bottomToTop = binding.previewBottomCard.id
                params.startToStart = ConstraintLayout.LayoutParams.UNSET
                params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                params.bottomMargin = 40
                params.marginEnd = 40
            }
            else -> {}
        }
        binding.previewHudContainer.layoutParams = params

        // Opacity
        binding.previewHudContainer.alpha = cfg.opacity

        // Show live values when available; otherwise keep the explicit N/A placeholders
        val snap = ServiceLocator.metrics.snapshot.value
        binding.previewHudFps.text = snap.fps.valueOrNull()?.let { "%.0f".format(it) } ?: "N/A"
        binding.previewHudCpu.text = snap.cpuTotalPercent.valueOrNull()?.let { "%.0f%%".format(it) } ?: "N/A"
        binding.previewHudGpu.text = snap.gpuUsagePercent.valueOrNull()?.let { "%.0f%%".format(it) } ?: "N/A"
        binding.previewHudRam.text = snap.ramUsedBytes.valueOrNull()?.let {
            "%.1f GB".format(it / 1_073_741_824.0)
        } ?: "N/A"
        val temp = snap.gpuTempC.valueOrNull() ?: snap.cpuTempC.valueOrNull() ?: snap.deviceTempC.valueOrNull()
        binding.previewHudTemp.text = temp?.let { "%.0f°C".format(it) } ?: "N/A"

        // The style is already persisted by the style screen; this screen is informational.
        binding.btnApplyPreview.setOnClickListener { finish() }
    }
}
