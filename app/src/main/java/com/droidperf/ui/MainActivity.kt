package com.droidperf.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.droidperf.R
import com.droidperf.databinding.ActivityMainBinding
import com.droidperf.di.ServiceLocator
import com.droidperf.domain.AccessMode
import com.droidperf.domain.MetricsSnapshot
import com.droidperf.domain.valueOrNull
import com.droidperf.overlay.OverlayService
import com.droidperf.settings.OverlayConfig
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private enum class Tab { MONITOR, OVERLAY, SETTINGS }
    private var currentTab = Tab.MONITOR
    private var isUpdatingControls = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Overlay continues regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ServiceLocator.settings.isSetupWizardCompleted()) {
            startActivity(Intent(this, SetupWizardActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        initGauges()
        setupBottomNav()
        setupMonitorTab()
        setupOverlayTab()
        setupSettingsTab()
        handleTabIntent(intent)
        observeState()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        handleTabIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTabIntent(intent)
    }

    private var elevatedAccessSheet: ElevatedAccessBottomSheet? = null

    private fun handleTabIntent(intent: Intent?) {
        val tabName = intent?.getStringExtra("EXTRA_TAB")
        val tab = when (tabName) {
            "OVERLAY" -> Tab.OVERLAY
            "SETTINGS" -> Tab.SETTINGS
            else -> Tab.MONITOR
        }
        switchTab(tab)

        if (intent?.getBooleanExtra("EXTRA_START_OVERLAY", false) == true) {
            if (!viewModel.hasElevatedAccess() && !ServiceLocator.settings.hasSeenPrivilegedPrompt()) {
                showElevatedAccessPrompt()
            } else {
                startOverlayInternal()
            }
        }

        if (intent?.getBooleanExtra("EXTRA_SHOW_ELEVATED_PROMPT", false) == true) {
            val expandGuide = intent.getBooleanExtra("EXTRA_EXPAND_GUIDE", false)
            showElevatedAccessPrompt(expandGuide = expandGuide)
        }
    }

    private fun showElevatedAccessPrompt(expandGuide: Boolean = false, onGranted: (() -> Unit)? = null) {
        if (elevatedAccessSheet == null) {
            elevatedAccessSheet = ElevatedAccessBottomSheet(this) {
                onGranted?.invoke() ?: startOverlayInternal()
            }
        }
        elevatedAccessSheet?.show(expandGuide)
    }

    private fun startOverlayInternal() {
        if (!Permissions.canDrawOverlays(this)) {
            startActivity(Permissions.overlaySettingsIntent(this))
            return
        }
        viewModel.setOverlayEnabled(true)
        val intent = Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)
        if (Permissions.needsNotificationPermission()) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        startForegroundService(intent)
    }

    private fun stopOverlayInternal() {
        viewModel.setOverlayEnabled(false)
        val intent = Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP)
        startService(intent)
    }

    private fun initGauges() {
        val accent = getColor(R.color.accent_green)
        binding.gaugeCpu.setGaugeColor(accent)
        binding.gaugeGpu.setGaugeColor(accent)
        binding.gaugeRam.setGaugeColor(accent)
    }

    private fun setupBottomNav() {
        binding.navTabMonitor.setOnClickListener { switchTab(Tab.MONITOR) }
        binding.navTabOverlay.setOnClickListener { switchTab(Tab.OVERLAY) }
        binding.navTabSettings.setOnClickListener { switchTab(Tab.SETTINGS) }
    }

    private fun switchTab(tab: Tab) {
        currentTab = tab
        binding.tabMonitorScroll.visibility = if (tab == Tab.MONITOR) View.VISIBLE else View.GONE
        binding.tabOverlayScroll.visibility = if (tab == Tab.OVERLAY) View.VISIBLE else View.GONE
        binding.tabSettingsScroll.visibility = if (tab == Tab.SETTINGS) View.VISIBLE else View.GONE

        val activeColor = getColor(R.color.nav_active)
        val inactiveColor = getColor(R.color.nav_inactive)

        updateNavTab(binding.navIconMonitor, binding.navTextMonitor, tab == Tab.MONITOR, activeColor, inactiveColor)
        updateNavTab(binding.navIconOverlay, binding.navTextOverlay, tab == Tab.OVERLAY, activeColor, inactiveColor)
        updateNavTab(binding.navIconSettings, binding.navTextSettings, tab == Tab.SETTINGS, activeColor, inactiveColor)
    }

    private fun updateNavTab(icon: ImageView, label: TextView, active: Boolean, activeColor: Int, inactiveColor: Int) {
        val color = if (active) activeColor else inactiveColor
        icon.setColorFilter(color)
        label.setTextColor(color)
        label.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun setupMonitorTab() {
        binding.headerGpuText.text = viewModel.gpuVendorName()
        binding.headerSocText.text = viewModel.socDescription().ifBlank { "qcom (UKEE)" }

        binding.btnToggleOverlay.setOnClickListener {
            val enable = !viewModel.config.value.enabled
            if (enable) {
                if (!viewModel.hasElevatedAccess() && !ServiceLocator.settings.hasSeenPrivilegedPrompt()) {
                    showElevatedAccessPrompt()
                    return@setOnClickListener
                }
                startOverlayInternal()
            } else {
                stopOverlayInternal()
            }
        }

        binding.cardAccessSummary.setOnClickListener {
            startActivity(Intent(this, AccessActivity::class.java))
        }

        binding.headerRootBadge.setOnClickListener {
            startActivity(Intent(this, AccessActivity::class.java))
        }
    }

    private fun setupOverlayTab() {
        binding.overlayMasterSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingControls && isChecked != viewModel.config.value.enabled) {
                if (isChecked) {
                    if (!viewModel.hasElevatedAccess() && !ServiceLocator.settings.hasSeenPrivilegedPrompt()) {
                        binding.overlayMasterSwitch.isChecked = false
                        showElevatedAccessPrompt()
                        return@setOnCheckedChangeListener
                    }
                    startOverlayInternal()
                } else {
                    stopOverlayInternal()
                }
            }
        }

        binding.switchFps.setOnCheckedChangeListener { _, v -> if (!isUpdatingControls) viewModel.updateConfig { it.copy(showFps = v) } }
        binding.switchCpu.setOnCheckedChangeListener { _, v -> if (!isUpdatingControls) viewModel.updateConfig { it.copy(showCpu = v) } }
        binding.switchGpu.setOnCheckedChangeListener { _, v -> if (!isUpdatingControls) viewModel.updateConfig { it.copy(showGpu = v) } }
        binding.switchRam.setOnCheckedChangeListener { _, v -> if (!isUpdatingControls) viewModel.updateConfig { it.copy(showRam = v) } }
        binding.switchBatteryTemp.setOnCheckedChangeListener { _, v -> if (!isUpdatingControls) viewModel.updateConfig { it.copy(showBatteryTemp = v, showTemperature = v) } }
        binding.switchCpuTemp.setOnCheckedChangeListener { _, v -> if (!isUpdatingControls) viewModel.updateConfig { it.copy(showCpuTemp = v) } }
        binding.switchFrameTime.setOnCheckedChangeListener { _, v -> if (!isUpdatingControls) viewModel.updateConfig { it.copy(showFrameTime = v) } }

        binding.rowStyleSelect.setOnClickListener {
            startActivity(Intent(this, OverlayStyleActivity::class.java))
        }

        binding.seekOpacity.setLabelFormatter { "${it.toInt()}%" }
        binding.seekOpacity.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isUpdatingControls) {
                val validProg = value.toInt().coerceIn(10, 100)
                binding.textOpacityVal.text = "$validProg%"
                viewModel.updateConfig { it.copy(textOpacity = validProg / 100f, opacity = validProg / 100f) }
            }
        }

        binding.seekBgOpacity.setLabelFormatter { "${it.toInt()}%" }
        binding.seekBgOpacity.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isUpdatingControls) {
                val validProg = value.toInt().coerceIn(0, 100)
                binding.textBgOpacityVal.text = "$validProg%"
                viewModel.updateConfig { it.copy(bgOpacity = validProg / 100f) }
            }
        }

        binding.overlayMenuBtn.setOnClickListener {
            startActivity(Intent(this, OverlayStyleActivity::class.java))
        }
    }

    private fun setupSettingsTab() {
        binding.rowOverlayOptions.setOnClickListener {
            startActivity(Intent(this, OverlayStyleActivity::class.java))
        }

        binding.rowPerformanceSettings.setOnClickListener {
            showSamplingIntervalDialog()
        }

        binding.rowAccessPermissions.setOnClickListener {
            startActivity(Intent(this, AccessActivity::class.java))
        }

        binding.rowDiagnostics.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
    }

    private fun showSamplingIntervalDialog() {
        val intervals = arrayOf("250 ms (Ultra fast)", "500 ms (Fast)", "1000 ms (Default)", "2000 ms (Battery saver)")
        val msValues = longArrayOf(250L, 500L, 1000L, 2000L)
        val curMs = viewModel.config.value.sampleIntervalMs
        val curIndex = msValues.indexOf(curMs).let { if (it < 0) 2 else it }

        AlertDialog.Builder(this)
            .setTitle("Sampling Interval")
            .setSingleChoiceItems(intervals, curIndex) { dialog, which ->
                viewModel.updateConfig { it.copy(sampleIntervalMs = msValues[which]) }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.config.collect { renderConfig(it) } }
                launch { viewModel.snapshot.collect { renderSnapshot(it) } }
            }
        }
    }

    private fun renderConfig(cfg: OverlayConfig) {
        isUpdatingControls = true

        val running = cfg.enabled

        // Overlay status & button in Monitor tab
        binding.monitorStatusDot.setBackgroundResource(if (running) R.drawable.bg_dot_running else R.drawable.bg_dot_stopped)
        binding.monitorStatusLabel.text = if (running) "Running" else "Stopped"
        binding.monitorStatusLabel.setTextColor(getColor(if (running) R.color.accent_green else R.color.on_surface_muted))

        binding.btnToggleOverlay.setBackgroundResource(if (running) R.drawable.bg_pill_button_red else R.drawable.bg_pill_button_green)
        binding.btnToggleOverlayIcon.setImageResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
        binding.btnToggleOverlayText.text = if (running) "STOP OVERLAY" else "START OVERLAY"

        // Overlay Tab controls
        binding.overlayMasterSwitch.isChecked = running
        binding.switchFps.isChecked = cfg.showFps
        binding.switchCpu.isChecked = cfg.showCpu
        binding.switchGpu.isChecked = cfg.showGpu
        binding.switchRam.isChecked = cfg.showRam
        binding.switchBatteryTemp.isChecked = cfg.showBatteryTemp
        binding.switchCpuTemp.isChecked = cfg.showCpuTemp
        binding.switchFrameTime.isChecked = cfg.showFrameTime

        binding.textStyleVal.text = cfg.osdStyle.name.lowercase().replaceFirstChar { it.uppercase() }
        val textOpacityPct = (cfg.textOpacity * 100).roundToInt().coerceIn(10, 100)
        binding.seekOpacity.value = textOpacityPct.toFloat()
        binding.textOpacityVal.text = "$textOpacityPct%"

        val bgOpacityPct = (cfg.bgOpacity * 100).roundToInt().coerceIn(0, 100)
        binding.seekBgOpacity.value = bgOpacityPct.toFloat()
        binding.textBgOpacityVal.text = "$bgOpacityPct%"

        isUpdatingControls = false
    }

    private fun renderSnapshot(snap: MetricsSnapshot) {
        val preferredMode = ServiceLocator.settings.getPreferredAccessMode()
        val isRooted = ServiceLocator.metrics.rootShell().isAvailable()
        val shizukuOk = ServiceLocator.shizuku.hasPermission()
        val isPreferredActive = when (preferredMode) {
            AccessMode.ROOT -> isRooted
            AccessMode.SHIZUKU -> shizukuOk
        }
        val hasElevated = isPreferredActive || isRooted || shizukuOk
        if (hasElevated) {
            ServiceLocator.settings.setPrivilegedPromptSeen(true)
        }

        val activeModeLabel = when {
            preferredMode == AccessMode.ROOT && isRooted -> "ROOT"
            preferredMode == AccessMode.SHIZUKU && shizukuOk -> "SHIZUKU"
            isRooted -> "ROOT"
            shizukuOk -> "SHIZUKU"
            else -> snap.accessLevel.label.uppercase()
        }

        binding.headerBadgeText.text = activeModeLabel
        val badgeColor = getColor(if (hasElevated) R.color.accent_green else R.color.missing)
        binding.headerBadgeText.setTextColor(badgeColor)
        binding.headerBadgeIcon.setColorFilter(badgeColor)

        // Gauges
        val cpuVal = snap.cpuTotalPercent.valueOrNull()?.toFloat()
        if (cpuVal != null) {
            binding.gaugeCpu.setGauge("CPU", "%.0f%%".format(cpuVal), cpuVal)
        } else {
            binding.gaugeCpu.setGauge("CPU", "—", 0f)
        }

        val gpuVal = snap.gpuUsagePercent.valueOrNull()?.toFloat()
        if (gpuVal != null) {
            binding.gaugeGpu.setGauge("GPU", "%.0f%%".format(gpuVal), gpuVal)
        } else {
            binding.gaugeGpu.setGauge("GPU", "—", 0f)
        }

        val ramUsed = snap.ramUsedBytes.valueOrNull()
        val ramTotal = snap.ramTotalBytes.valueOrNull()
        if (ramUsed != null && ramTotal != null && ramTotal > 0) {
            val gb = ramUsed / 1_073_741_824.0
            val pct = (ramUsed.toFloat() / ramTotal.toFloat()) * 100f
            binding.gaugeRam.setGauge("RAM", "%.1f GB".format(gb), pct)
        } else {
            binding.gaugeRam.setGauge("RAM", "—", 0f)
        }

        // Access rows in Monitor tab
        val overlayOk = Permissions.canDrawOverlays(this)
        val usageOk = viewModel.hasUsageAccess()

        val isShizuku = preferredMode == AccessMode.SHIZUKU || (!isRooted && shizukuOk)
        if (isShizuku) {
            binding.accessPrivilegeLabel.text = "Shizuku"
            binding.accessPrivilegeIcon.setImageResource(R.drawable.ic_shizuku)
            binding.accessPrivilegeIcon.setColorFilter(getColor(if (shizukuOk) R.color.accent_cyan else R.color.on_surface_muted))
        } else {
            binding.accessPrivilegeLabel.text = "Root"
            binding.accessPrivilegeIcon.setImageResource(R.drawable.ic_security)
            binding.accessPrivilegeIcon.setColorFilter(getColor(if (isRooted) R.color.accent_green else R.color.on_surface_muted))
        }

        val privilegedOk = if (isShizuku) shizukuOk else isRooted
        binding.accessIconRoot.visibility = View.VISIBLE
        binding.accessIconRoot.setImageResource(if (privilegedOk) R.drawable.ic_check_circle else R.drawable.ic_info)
        binding.accessIconRoot.setColorFilter(getColor(if (privilegedOk) R.color.accent_green else R.color.missing))

        binding.accessIconOverlay.setImageResource(if (overlayOk) R.drawable.ic_check_circle else R.drawable.ic_info)
        binding.accessIconOverlay.setColorFilter(getColor(if (overlayOk) R.color.accent_green else R.color.missing))

        binding.accessIconUsage.setImageResource(if (usageOk) R.drawable.ic_check_circle else R.drawable.ic_info)
        binding.accessIconUsage.setColorFilter(getColor(if (usageOk) R.color.accent_green else R.color.warn))

        // Settings Tab: Single minimal access card
        binding.textAccessSubtitle.text = when {
            preferredMode == AccessMode.ROOT && isRooted -> "Root active"
            preferredMode == AccessMode.SHIZUKU && shizukuOk -> "Shizuku active"
            hasElevated -> "Privileged access granted"
            else -> "Root or Shizuku required"
        }
        binding.textAccessSubtitle.setTextColor(
            getColor(if (hasElevated) R.color.accent_green else R.color.missing)
        )
        binding.badgeAccessStatus.text = activeModeLabel
        val accessColor = getColor(if (hasElevated) R.color.accent_green else R.color.accent_cyan)
        binding.badgeAccessStatus.setTextColor(accessColor)
        binding.badgeAccessStatus.setBackgroundResource(
            if (hasElevated) R.drawable.bg_card_selected else R.drawable.bg_badge_root
        )
        binding.iconAccessStatus.setColorFilter(accessColor)

        // Device chip info
        binding.headerGpuText.text = viewModel.gpuVendorName()
        binding.headerSocText.text = viewModel.socDescription().ifBlank { "qcom (UKEE)" }
    }

    override fun onResume() {
        super.onResume()
        runCatching { ServiceLocator.shizuku.bindIfPermitted() }
        viewModel.refreshCapabilities()
        viewModel.startUiSampling()
        elevatedAccessSheet?.refreshState()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopUiSampling()
    }

    override fun onDestroy() {
        elevatedAccessSheet?.dismiss()
        elevatedAccessSheet = null
        super.onDestroy()
    }
}
