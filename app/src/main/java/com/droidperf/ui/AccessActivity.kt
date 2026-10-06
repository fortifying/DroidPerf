package com.droidperf.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.droidperf.R
import com.droidperf.databinding.ActivityAccessBinding
import com.droidperf.di.ServiceLocator
import com.droidperf.domain.AccessMode
import com.droidperf.system.shell.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class AccessActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccessBinding
    private var elevatedSheet: ElevatedAccessBottomSheet? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            runCatching { ServiceLocator.shizuku.bindIfPermitted() }
            updateUi()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            updateUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccessBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        } catch (_: Throwable) {}

        binding.btnAccessBack.setOnClickListener { finish() }

        // Mode selection cards
        binding.cardModeRoot.setOnClickListener {
            ServiceLocator.settings.setPreferredAccessMode(AccessMode.ROOT)
            lifecycleScope.launch(Dispatchers.IO) {
                ServiceLocator.metrics.rootShell().isAvailable()
                withContext(Dispatchers.Main) { updateUi() }
            }
        }

        binding.cardModeShizuku.setOnClickListener {
            ServiceLocator.settings.setPreferredAccessMode(AccessMode.SHIZUKU)
            runCatching { ServiceLocator.shizuku.bindIfPermitted() }
            updateUi()
        }

        // Action buttons
        binding.btnRequestRoot.setOnClickListener {
            verifyRootAccess()
        }

        binding.btnRequestShizuku.setOnClickListener {
            handleShizukuAction()
        }

        binding.btnGrantOverlay.setOnClickListener {
            startActivity(Permissions.overlaySettingsIntent(this))
        }

        binding.btnGrantUsage.setOnClickListener {
            startActivity(Permissions.usageAccessSettingsIntent())
        }
    }

    override fun onResume() {
        super.onResume()
        runCatching { ServiceLocator.shizuku.bindIfPermitted() }
        lifecycleScope.launch(Dispatchers.IO) {
            ServiceLocator.metrics.rootShell().isAvailable()
            withContext(Dispatchers.Main) { updateUi() }
        }
    }

    private fun verifyRootAccess() {
        lifecycleScope.launch(Dispatchers.IO) {
            val isRooted = ServiceLocator.metrics.rootShell().requestRoot()
            withContext(Dispatchers.Main) {
                if (isRooted) {
                    ServiceLocator.settings.setPreferredAccessMode(AccessMode.ROOT)
                    ServiceLocator.settings.setPrivilegedPromptSeen(true)
                    Toast.makeText(this@AccessActivity, "Superuser access verified and active!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@AccessActivity, "Root (su) not available or denied.", Toast.LENGTH_SHORT).show()
                }
                updateUi()
            }
        }
    }

    private fun handleShizukuAction() {
        val shizuku = ServiceLocator.shizuku
        when {
            shizuku.hasPermission() -> {
                shizuku.bindIfPermitted()
                ServiceLocator.settings.setPrivilegedPromptSeen(true)
                updateUi()
            }
            shizuku.isBinderAvailable() -> {
                shizuku.requestPermission { granted ->
                    runOnUiThread {
                        if (granted) {
                            Toast.makeText(this@AccessActivity, "Shizuku permission granted!", Toast.LENGTH_SHORT).show()
                            ServiceLocator.settings.setPrivilegedPromptSeen(true)
                        } else {
                            Toast.makeText(this@AccessActivity, "Shizuku permission denied.", Toast.LENGTH_SHORT).show()
                        }
                        updateUi()
                    }
                }
            }
            shizuku.isInstalled() -> {
                val launchIntent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (launchIntent != null) {
                    startActivity(launchIntent)
                } else {
                    showElevatedGuide()
                }
            }
            else -> {
                showElevatedGuide()
            }
        }
    }

    private fun showElevatedGuide() {
        if (elevatedSheet == null) {
            elevatedSheet = ElevatedAccessBottomSheet(this) { updateUi() }
        }
        elevatedSheet?.show(forceExpandGuide = true)
    }

    private fun updateUi() {
        val preferredMode = ServiceLocator.settings.getPreferredAccessMode()
        val isRooted = ServiceLocator.metrics.rootShell().isAvailable()
        val hasSu = RootShell.hasSuBinary()
        val rootMethod = RootShell.getRootMethod()
        val overlayOk = Permissions.canDrawOverlays(this)
        val usageOk = ServiceLocator.metrics.hasUsageAccess()
        val shizuku = ServiceLocator.shizuku
        val shizukuOk = shizuku.hasPermission()
        val shizukuBinderAlive = shizuku.isBinderAvailable()
        val shizukuInstalled = shizuku.isInstalled()

        val isRootSelected = preferredMode == AccessMode.ROOT
        binding.cardModeRoot.setBackgroundResource(
            if (isRootSelected) R.drawable.bg_card_selected else R.drawable.bg_card_dark
        )
        binding.iconRadioRoot.setImageResource(
            if (isRootSelected) R.drawable.ic_check_circle else R.drawable.ic_radio_unchecked
        )
        binding.iconRadioRoot.setColorFilter(
            getColor(if (isRootSelected) R.color.accent_green else R.color.on_surface_faint)
        )

        binding.accessRootStatus.text = when {
            isRooted -> "Granted"
            hasSu -> "Permission required"
            else -> "Not available"
        }
        binding.accessRootStatus.setTextColor(
            getColor(
                when {
                    isRooted -> R.color.accent_green
                    hasSu -> R.color.warn
                    else -> R.color.on_surface_muted
                }
            )
        )
        binding.accessRootIcon.visibility = if (isRooted) View.VISIBLE else View.GONE
        binding.accessRootMethod.text = when {
            isRooted -> rootMethod
            hasSu -> "$rootMethod (Tap Request Root)"
            else -> "su binary not found"
        }
        binding.btnRequestRoot.visibility = if (isRootSelected && !isRooted) View.VISIBLE else View.GONE

        val isShizukuSelected = preferredMode == AccessMode.SHIZUKU
        binding.cardModeShizuku.setBackgroundResource(
            if (isShizukuSelected) R.drawable.bg_card_selected else R.drawable.bg_card_dark
        )
        binding.iconRadioShizuku.setImageResource(
            if (isShizukuSelected) R.drawable.ic_check_circle else R.drawable.ic_radio_unchecked
        )
        binding.iconRadioShizuku.setColorFilter(
            getColor(if (isShizukuSelected) R.color.accent_green else R.color.on_surface_faint)
        )

        binding.accessShizukuStatus.text = when {
            shizukuOk -> "Granted"
            shizukuBinderAlive -> "Permission required"
            shizukuInstalled -> "Service stopped"
            else -> "Not installed"
        }
        binding.accessShizukuStatus.setTextColor(
            getColor(
                when {
                    shizukuOk -> R.color.accent_green
                    shizukuBinderAlive -> R.color.warn
                    else -> R.color.missing
                }
            )
        )

        binding.accessShizukuDetail.text = when {
            shizukuOk -> "Shizuku service is connected and authorized. Elevated telemetry is fully active."
            shizukuBinderAlive -> "Shizuku service is running. Tap below to authorize DroidPerf."
            shizukuInstalled -> "Shizuku is installed, but the service is not currently running. Start it via Wireless Debugging."
            else -> "Shizuku is not installed. You can install it from Play Store or GitHub to enable elevated stats without root."
        }

        binding.btnRequestShizuku.visibility = if (isShizukuSelected && !shizukuOk) View.VISIBLE else View.GONE
        binding.textRequestShizuku.text = when {
            shizukuBinderAlive -> "Request Shizuku Permission"
            shizukuInstalled -> "Open Shizuku App"
            else -> "Install Shizuku & View Guide"
        }

        // Overlay
        binding.accessOverlayStatus.text = if (overlayOk) "Granted" else "Permission required"
        binding.accessOverlayStatus.setTextColor(
            getColor(if (overlayOk) R.color.accent_green else R.color.missing)
        )
        binding.accessOverlayIcon.visibility = if (overlayOk) View.VISIBLE else View.GONE
        binding.btnGrantOverlay.visibility = if (overlayOk) View.GONE else View.VISIBLE

        // Usage access
        binding.accessUsageStatus.text = if (usageOk) "Granted" else "Access required"
        binding.accessUsageStatus.setTextColor(
            getColor(if (usageOk) R.color.accent_green else R.color.missing)
        )
        binding.accessUsageIcon.visibility = if (usageOk) View.VISIBLE else View.GONE
        binding.btnGrantUsage.visibility = if (usageOk) View.GONE else View.VISIBLE

        val isPreferredActive = when (preferredMode) {
            AccessMode.ROOT -> isRooted
            AccessMode.SHIZUKU -> shizukuOk
        }

        if (isPreferredActive) {
            binding.accessStatusBanner.setBackgroundResource(R.drawable.bg_card_selected)
            binding.accessStatusBannerIcon.setImageResource(R.drawable.ic_check_circle)
            binding.accessStatusBannerIcon.setColorFilter(getColor(R.color.accent_green))
            binding.accessStatusBannerText.text =
                "Privileged access active via ${preferredMode.displayName}.\nReal-time FPS and deep hardware telemetry are unlocked."
        } else {
            binding.accessStatusBanner.setBackgroundResource(R.drawable.bg_card_dark)
            binding.accessStatusBannerIcon.setImageResource(R.drawable.ic_info)
            binding.accessStatusBannerIcon.setColorFilter(getColor(R.color.warn))
            binding.accessStatusBannerText.text =
                "Privileged access required (${preferredMode.displayName}).\nPlease authorize above to enable accurate FPS and hardware metrics."
        }
    }

    override fun onDestroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (_: Throwable) {}
        elevatedSheet?.dismiss()
        elevatedSheet = null
        super.onDestroy()
    }
}
