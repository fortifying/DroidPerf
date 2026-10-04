package com.droidperf.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.droidperf.R
import com.droidperf.databinding.ActivitySetupWizardBinding
import com.droidperf.di.ServiceLocator
import com.droidperf.domain.AccessMode
import com.droidperf.system.shell.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class SetupWizardActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupWizardBinding
    private var selectedMode: AccessMode = AccessMode.ROOT

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            runCatching { ServiceLocator.shizuku.bindIfPermitted() }
            updateUi()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { updateUi() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        // Exit app if back is pressed without completing setup
        onBackPressedDispatcher.addCallback(this) {
            finishAffinity()
        }

        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        } catch (_: Throwable) {}

        // Default mode based on device capabilities
        selectedMode = when {
            ServiceLocator.metrics.rootShell().isAvailable() -> AccessMode.ROOT
            ServiceLocator.shizuku.hasPermission() -> AccessMode.SHIZUKU
            RootShell.hasSuBinary() -> AccessMode.ROOT
            ServiceLocator.shizuku.isBinderAvailable() -> AccessMode.SHIZUKU
            else -> AccessMode.ROOT
        }

        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        runCatching { ServiceLocator.shizuku.bindIfPermitted() }
        lifecycleScope.launch(Dispatchers.IO) {
            ServiceLocator.metrics.rootShell().isAvailable()
            withContext(Dispatchers.Main) { updateUi() }
        }
    }

    override fun onDestroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun setupClickListeners() {
        binding.cardWizardRoot.setOnClickListener {
            selectedMode = AccessMode.ROOT
            lifecycleScope.launch(Dispatchers.IO) {
                ServiceLocator.metrics.rootShell().isAvailable()
                withContext(Dispatchers.Main) { updateUi() }
            }
        }

        binding.cardWizardShizuku.setOnClickListener {
            selectedMode = AccessMode.SHIZUKU
            runCatching { ServiceLocator.shizuku.bindIfPermitted() }
            updateUi()
        }

        binding.btnRequestRoot.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = ServiceLocator.metrics.rootShell().requestRoot()
                withContext(Dispatchers.Main) {
                    if (ok) {
                        Toast.makeText(this@SetupWizardActivity, "Root access granted!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@SetupWizardActivity, "Root (su) not available or denied.", Toast.LENGTH_SHORT).show()
                    }
                    updateUi()
                }
            }
        }

        binding.btnRequestShizuku.setOnClickListener {
            val shizuku = ServiceLocator.shizuku
            when {
                shizuku.hasPermission() -> {
                    shizuku.bindIfPermitted()
                    updateUi()
                }
                shizuku.isBinderAvailable() -> {
                    shizuku.requestPermission { granted ->
                        runOnUiThread {
                            if (granted) {
                                Toast.makeText(this@SetupWizardActivity, "Shizuku permission granted!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@SetupWizardActivity, "Shizuku permission denied.", Toast.LENGTH_SHORT).show()
                            }
                            updateUi()
                        }
                    }
                }
                shizuku.isInstalled() -> {
                    val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (intent != null) startActivity(intent)
                }
                else -> {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api")))
                    } catch (_: Throwable) {
                        Toast.makeText(this, "Could not open store for Shizuku", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        binding.btnGrantOverlay.setOnClickListener {
            startActivity(Permissions.overlaySettingsIntent(this))
        }

        binding.btnGrantUsage.setOnClickListener {
            startActivity(Permissions.usageAccessSettingsIntent())
        }

        binding.btnCompleteSetup.setOnClickListener {
            val isRooted = ServiceLocator.metrics.rootShell().isAvailable()
            val shizukuOk = ServiceLocator.shizuku.hasPermission()
            val isPrivilegedReady = when (selectedMode) {
                AccessMode.ROOT -> isRooted
                AccessMode.SHIZUKU -> shizukuOk
            }

            if (!isPrivilegedReady) {
                val msg = if (selectedMode == AccessMode.ROOT) {
                    "Please grant Root access first to proceed."
                } else {
                    "Please authorize Shizuku first to proceed."
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Save configuration and proceed
            ServiceLocator.settings.setPreferredAccessMode(selectedMode)
            ServiceLocator.settings.setSetupWizardCompleted(true)
            ServiceLocator.settings.setPrivilegedPromptSeen(true)
            ServiceLocator.metrics.refreshCapabilities()

            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun updateUi() {
        val isRooted = ServiceLocator.metrics.rootShell().isAvailable()
        val hasSu = RootShell.hasSuBinary()
        val rootMethod = RootShell.getRootMethod()

        val shizuku = ServiceLocator.shizuku
        val shizukuOk = shizuku.hasPermission()
        val shizukuBinderAlive = shizuku.isBinderAvailable()
        val shizukuInstalled = shizuku.isInstalled()

        val overlayOk = Permissions.canDrawOverlays(this)
        val usageOk = ServiceLocator.metrics.hasUsageAccess()

        // 1. Root Card Appearance
        val isRootSelected = selectedMode == AccessMode.ROOT
        binding.cardWizardRoot.setBackgroundResource(
            if (isRootSelected) R.drawable.bg_card_selected else R.drawable.bg_card_dark
        )
        binding.iconRadioRoot.setImageResource(
            if (isRootSelected) R.drawable.ic_check_circle else R.drawable.ic_radio_unchecked
        )
        binding.iconRadioRoot.setColorFilter(
            getColor(if (isRootSelected) R.color.accent_green else R.color.on_surface_faint)
        )

        binding.textRootStatus.text = when {
            isRooted -> "Granted"
            hasSu -> "Permission required"
            else -> "Not available"
        }
        binding.textRootStatus.setTextColor(
            getColor(
                when {
                    isRooted -> R.color.accent_green
                    hasSu -> R.color.warn
                    else -> R.color.on_surface_muted
                }
            )
        )
        binding.iconRootChecked.visibility = if (isRooted) View.VISIBLE else View.GONE
        binding.textRootMethod.text = when {
            isRooted -> rootMethod
            hasSu -> "$rootMethod (Tap Request Root)"
            else -> "su binary not found"
        }
        binding.btnRequestRoot.visibility = if (isRootSelected && !isRooted) View.VISIBLE else View.GONE

        // 2. Shizuku Card Appearance
        val isShizukuSelected = selectedMode == AccessMode.SHIZUKU
        binding.cardWizardShizuku.setBackgroundResource(
            if (isShizukuSelected) R.drawable.bg_card_selected else R.drawable.bg_card_dark
        )
        binding.iconRadioShizuku.setImageResource(
            if (isShizukuSelected) R.drawable.ic_check_circle else R.drawable.ic_radio_unchecked
        )
        binding.iconRadioShizuku.setColorFilter(
            getColor(if (isShizukuSelected) R.color.accent_green else R.color.on_surface_faint)
        )

        binding.textShizukuStatus.text = when {
            shizukuOk -> "Granted"
            shizukuBinderAlive -> "Permission required"
            shizukuInstalled -> "Service stopped"
            else -> "Not installed"
        }
        binding.textShizukuStatus.setTextColor(
            getColor(
                when {
                    shizukuOk -> R.color.accent_green
                    shizukuBinderAlive -> R.color.warn
                    else -> R.color.missing
                }
            )
        )

        binding.layoutShizukuDetail.visibility = if (isShizukuSelected) View.VISIBLE else View.GONE
        binding.btnRequestShizuku.visibility = if (isShizukuSelected && !shizukuOk) View.VISIBLE else View.GONE
        binding.btnRequestShizuku.text = when {
            shizukuBinderAlive -> "Authorize Shizuku"
            shizukuInstalled -> "Open Shizuku App"
            else -> "Install Shizuku"
        }
        binding.textShizukuDetail.text = when {
            shizukuOk -> "Shizuku service authorized."
            shizukuBinderAlive -> "Shizuku service is running. Tap below to authorize DroidPerf."
            shizukuInstalled -> "Shizuku is installed, but service is not running. Open Shizuku to start it."
            else -> "Shizuku is not installed. Install it from the Play Store or choose Root above."
        }

        // 3. System Permissions
        binding.btnGrantOverlay.visibility = if (overlayOk) View.GONE else View.VISIBLE
        binding.iconOverlayDone.visibility = if (overlayOk) View.VISIBLE else View.GONE
        binding.textOverlayStatus.text = if (overlayOk) "Granted" else "Permission required"
        binding.textOverlayStatus.setTextColor(getColor(if (overlayOk) R.color.accent_green else R.color.warn))

        binding.btnGrantUsage.visibility = if (usageOk) View.GONE else View.VISIBLE
        binding.iconUsageDone.visibility = if (usageOk) View.VISIBLE else View.GONE
        binding.textUsageStatus.text = if (usageOk) "Granted" else "Permission required"
        binding.textUsageStatus.setTextColor(getColor(if (usageOk) R.color.accent_green else R.color.warn))

        // 4. Validation and Bottom Button State
        val isPrivilegedReady = when (selectedMode) {
            AccessMode.ROOT -> isRooted
            AccessMode.SHIZUKU -> shizukuOk
        }

        if (isPrivilegedReady) {
            val modeName = if (selectedMode == AccessMode.ROOT) "Root" else "Shizuku"
            binding.textWizardNotice.text = "Privileged access active via $modeName. Ready to start!"
            binding.textWizardNotice.setTextColor(getColor(R.color.accent_green))
            binding.btnCompleteSetup.isEnabled = true
            binding.btnCompleteSetup.setBackgroundResource(R.drawable.bg_pill_button_green)
            binding.btnCompleteSetupText.setTextColor(getColor(R.color.on_brand_primary))
        } else {
            val missingName = if (selectedMode == AccessMode.ROOT) "Root" else "Shizuku"
            binding.textWizardNotice.text = "$missingName access is not granted. Grant access to continue."
            binding.textWizardNotice.setTextColor(getColor(R.color.warn))
            binding.btnCompleteSetup.isEnabled = false
            binding.btnCompleteSetup.setBackgroundResource(R.drawable.bg_pill_button_disabled)
            binding.btnCompleteSetupText.setTextColor(getColor(R.color.on_surface_muted))
        }
    }
}
