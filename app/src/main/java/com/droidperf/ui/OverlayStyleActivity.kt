package com.droidperf.ui

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.droidperf.R
import com.droidperf.databinding.ActivityOverlayStyleBinding
import com.droidperf.di.ServiceLocator
import com.droidperf.settings.OsdStyle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class OverlayStyleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOverlayStyleBinding
    private val settings get() = ServiceLocator.settings
    private var updatingUi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOverlayStyleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        binding.btnStyleBack.setOnClickListener { finish() }

        setupStyleSelectors()
        setupSliders()

        observeConfig()
    }

    private fun setupStyleSelectors() {
        binding.cardStyleClassic.setOnClickListener {
            settings.update { it.copy(osdStyle = OsdStyle.CLASSIC) }
        }
        binding.cardStyleModern.setOnClickListener {
            settings.update { it.copy(osdStyle = OsdStyle.MODERN) }
        }
        binding.cardStyleRtss.setOnClickListener {
            settings.update { it.copy(osdStyle = OsdStyle.RTSS) }
        }
    }

    private fun setupSliders() {
        binding.seekSize.setLabelFormatter { "${it.toInt()}%" }
        binding.seekSize.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !updatingUi) {
                val pct = value.toInt().coerceIn(50, 150)
                binding.textSizeVal.text = "$pct%"
                val spVal = (11f * (pct / 100f)).coerceIn(8f, 22f)
                settings.update { it.copy(textSizeSp = spVal) }
            }
        }

        binding.seekTextOpacity.setLabelFormatter { "${it.toInt()}%" }
        binding.seekTextOpacity.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !updatingUi) {
                val pct = value.toInt().coerceIn(10, 100)
                binding.textTextOpacityVal.text = "$pct%"
                settings.update { it.copy(textOpacity = pct / 100f, opacity = pct / 100f) }
            }
        }

        binding.seekBgOpacity.setLabelFormatter { "${it.toInt()}%" }
        binding.seekBgOpacity.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !updatingUi) {
                val pct = value.toInt().coerceIn(0, 100)
                binding.textBgOpacityVal.text = "$pct%"
                settings.update { it.copy(bgOpacity = pct / 100f) }
            }
        }
    }

    private fun observeConfig() {
        lifecycleScope.launch {
            settings.config.collectLatest { cfg ->
                updatingUi = true

                // Update Style cards
                val isClassic = cfg.osdStyle == OsdStyle.CLASSIC
                val isModern = cfg.osdStyle == OsdStyle.MODERN
                val isRtss = cfg.osdStyle == OsdStyle.RTSS

                updateCardSelect(binding.cardStyleClassic, binding.textStyleClassic, isClassic)
                updateCardSelect(binding.cardStyleModern, binding.textStyleModern, isModern)
                updateCardSelect(binding.cardStyleRtss, binding.textStyleRtss, isRtss)

                binding.iconStyleClassic.setColorFilter(getColor(if (isClassic) R.color.accent_green else R.color.on_surface_muted))
                binding.iconStyleModern.setColorFilter(getColor(if (isModern) R.color.accent_green else R.color.on_surface_muted))
                binding.iconStyleRtss.setColorFilter(getColor(if (isRtss) R.color.accent_green else R.color.on_surface_muted))

                // Update Sliders
                val sizePct = ((cfg.textSizeSp / 11f) * 100).roundToInt().coerceIn(50, 150)
                binding.seekSize.value = sizePct.toFloat()
                binding.textSizeVal.text = "$sizePct%"

                val textOpacityPct = (cfg.textOpacity * 100).roundToInt().coerceIn(10, 100)
                binding.seekTextOpacity.value = textOpacityPct.toFloat()
                binding.textTextOpacityVal.text = "$textOpacityPct%"

                val bgOpacityPct = (cfg.bgOpacity * 100).roundToInt().coerceIn(0, 100)
                binding.seekBgOpacity.value = bgOpacityPct.toFloat()
                binding.textBgOpacityVal.text = "$bgOpacityPct%"

                updatingUi = false
            }
        }
    }

    private fun updateCardSelect(card: LinearLayout, text: TextView, selected: Boolean) {
        card.setBackgroundResource(if (selected) R.drawable.bg_card_selected else R.drawable.bg_card_dark)
        text.setTextColor(getColor(if (selected) R.color.accent_green else R.color.on_surface_muted))
    }
}
