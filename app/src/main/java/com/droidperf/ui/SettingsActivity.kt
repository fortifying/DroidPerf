package com.droidperf.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.droidperf.databinding.ActivitySettingsBinding
import com.droidperf.di.ServiceLocator
import com.droidperf.settings.OsdStyle
import com.droidperf.settings.OverlayConfig
import com.droidperf.settings.OverlayPreset
import com.droidperf.settings.SettingsRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Settings screen. Every control writes through [SettingsRepository], which the running
 * overlay service observes, so changes apply live without a restart.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val settings: SettingsRepository get() = ServiceLocator.settings

    /** Guards the SeekBar/switch listeners while we programmatically set their values. */
    private var bindingUp = false

    private val intervalValues = (250..5000 step 250).toList()

    private val metricToggles: List<Pair<String, (OverlayConfig, Boolean) -> OverlayConfig>> =
        listOf(
            "FPS" to { c, v -> c.copy(showFps = v) },
            "CPU total" to { c, v -> c.copy(showCpu = v) },
            "CPU Temp" to { c, v -> c.copy(showCpuTemp = v) },
            "Per-core CPU" to { c, v -> c.copy(showPerCore = v) },
            "GPU" to { c, v -> c.copy(showGpu = v) },
            "RAM" to { c, v -> c.copy(showRam = v) },
            "Battery Temp" to { c, v -> c.copy(showBatteryTemp = v, showTemperature = v) },
            "Battery" to { c, v -> c.copy(showBattery = v) },
            "Network" to { c, v -> c.copy(showNetwork = v, networkEnabled = v) },
            "Display info" to { c, v -> c.copy(showDisplay = v) },
            "Foreground app" to { c, v -> c.copy(showApp = v) },
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        setupPresets()
        setupStyleSpinner()
        setupSliders()
        setupMetricToggles()
        observe()
    }

    /** Selects which renderer the overlay uses; data and geometry are unchanged. */
    private fun setupStyleSpinner() {
        val labels = OsdStyle.entries.map {
            when (it) {
                OsdStyle.CLASSIC -> "Classic"
                OsdStyle.MODERN -> "Modern"
                OsdStyle.RTSS -> "RTSS (Afterburner)"
            }
        }
        binding.styleSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        binding.styleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (bindingUp) return
                settings.update { it.copy(osdStyle = OsdStyle.entries[position]) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupPresets() {
        val presets = OverlayPreset.entries.map { it.name }
        binding.presetSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, presets
        )
        binding.presetSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, view: android.view.View?,
                    position: Int, id: Long,
                ) {
                    if (bindingUp) return
                    settings.setPreset(OverlayPreset.entries[position])
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
    }

    private fun setupSliders() {
        binding.textSizeSeek.setLabelFormatter { "${it.toInt()} sp" }
        binding.textSizeSeek.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !bindingUp) {
                settings.update { it.copy(textSizeSp = value) }
            }
        }

        binding.opacitySeek.setLabelFormatter { "${it.toInt()}%" }
        binding.opacitySeek.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !bindingUp) {
                val factor = (value / 100f).coerceAtLeast(0.1f)
                settings.update { it.copy(textOpacity = factor, opacity = factor) }
            }
        }

        binding.intervalSeek.setLabelFormatter { "${intervalValues[it.toInt().coerceIn(0, intervalValues.lastIndex)]} ms" }
        binding.intervalSeek.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !bindingUp) {
                val idx = value.toInt().coerceIn(0, intervalValues.lastIndex)
                settings.update { it.copy(sampleIntervalMs = intervalValues[idx].toLong()) }
                binding.intervalLabel.text = "${intervalValues[idx]} ms"
            }
        }
    }

    private fun setupMetricToggles() {
        binding.metricsContainer.removeAllViews()
        for ((label, apply) in metricToggles) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            val text = TextView(this).apply {
                this.text = label
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val toggle = Switch(this)
            toggle.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                if (!bindingUp) settings.update { apply(it, checked) }
            }
            row.addView(text)
            row.addView(toggle)
            binding.metricsContainer.addView(row)
            metricSwitches += toggle to label
        }
    }

    private val metricSwitches = mutableListOf<Pair<Switch, String>>()

    private fun observe() {
        lifecycleScope.launch {
            settings.config.collectLatest { cfg ->
                bindingUp = true

                binding.enabledSwitch.isChecked = cfg.enabled
                binding.compactSwitch.isChecked = cfg.compactMode
                binding.enabledSwitch.setOnCheckedChangeListener { _, v ->
                    if (!bindingUp) settings.update { it.copy(enabled = v) }
                }
                binding.compactSwitch.setOnCheckedChangeListener { _, v ->
                    if (!bindingUp) settings.update { it.copy(compactMode = v) }
                }

                binding.textSizeSeek.value = cfg.textSizeSp.coerceIn(7f, 28f)
                binding.opacitySeek.value = (cfg.textOpacity * 100).roundToInt().coerceIn(10, 100).toFloat()

                val idx = intervalValues.indexOfFirst { it >= cfg.sampleIntervalMs }
                    .let { if (it < 0) intervalValues.lastIndex else it }
                binding.intervalSeek.value = idx.toFloat()
                binding.intervalLabel.text = "${intervalValues[idx]} ms"

                binding.presetSpinner.setSelection(OverlayPreset.entries.indexOf(cfg.preset))
                binding.styleSpinner.setSelection(OsdStyle.entries.indexOf(cfg.osdStyle))

                val values = listOf(
                    cfg.showFps, cfg.showCpu, cfg.showCpuTemp, cfg.showPerCore, cfg.showGpu,
                    cfg.showRam, cfg.showBatteryTemp, cfg.showBattery, cfg.showNetwork,
                    cfg.showDisplay, cfg.showApp,
                )
                metricSwitches.forEachIndexed { i, (sw, _) ->
                    if (i < values.size) sw.isChecked = values[i]
                }

                bindingUp = false
            }
        }
    }
}
