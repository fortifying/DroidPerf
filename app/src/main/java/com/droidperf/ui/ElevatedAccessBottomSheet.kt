package com.droidperf.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.droidperf.R
import com.droidperf.databinding.BottomSheetElevatedAccessBinding
import com.droidperf.di.ServiceLocator
import com.droidperf.domain.AccessMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ElevatedAccessBottomSheet(
    private val activity: Activity,
    private val onStartOverlayRequested: () -> Unit
) {

    private val dialog = BottomSheetDialog(activity, R.style.ThemeOverlay_PerfOverlay_BottomSheetDialog)
    private val binding = BottomSheetElevatedAccessBinding.inflate(LayoutInflater.from(activity))
    private var isGuideExpanded = false

    init {
        dialog.setContentView(binding.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        setupListeners()
        dialog.setOnDismissListener {
            ServiceLocator.settings.setPrivilegedPromptSeen(true)
        }
    }

    fun show(forceExpandGuide: Boolean = false) {
        refreshState()
        val shizuku = ServiceLocator.shizuku
        if (forceExpandGuide || (!shizuku.hasPermission() && !shizuku.isBinderAvailable())) {
            expandGuide(true)
        }
        dialog.show()
    }

    fun dismiss() {
        dialog.dismiss()
    }

    fun refreshState() {
        val shizuku = ServiceLocator.shizuku
        val rootShell = ServiceLocator.metrics.rootShell()
        val isRooted = rootShell.isAvailable()
        val isShizukuPermitted = shizuku.hasPermission()
        val isShizukuBinderAlive = shizuku.isBinderAvailable()
        val isShizukuInstalled = shizuku.isInstalled()
        val hasElevated = isRooted || isShizukuPermitted

        // 1. Shizuku Card UI
        when {
            isShizukuPermitted -> {
                binding.shizukuStatusDot.setBackgroundResource(R.drawable.bg_dot_running)
                binding.shizukuStatusText.text = "Permission Granted"
                binding.shizukuStatusText.setTextColor(activity.getColor(R.color.accent_green))
                binding.btnShizukuAction.text = "Authorized"
                binding.btnShizukuAction.setBackgroundResource(R.drawable.bg_pill_button_disabled)
                binding.btnShizukuAction.setTextColor(activity.getColor(R.color.on_surface_muted))
                binding.btnShizukuAction.isEnabled = false
            }
            isShizukuBinderAlive -> {
                binding.shizukuStatusDot.setBackgroundResource(R.drawable.bg_dot_running)
                binding.shizukuStatusText.text = "Service running — Authorization needed"
                binding.shizukuStatusText.setTextColor(activity.getColor(R.color.warn))
                binding.btnShizukuAction.text = "Authorize Shizuku"
                binding.btnShizukuAction.setBackgroundResource(R.drawable.bg_pill_button_cyan)
                binding.btnShizukuAction.setTextColor(activity.getColor(R.color.surface))
                binding.btnShizukuAction.isEnabled = true
            }
            isShizukuInstalled -> {
                binding.shizukuStatusDot.setBackgroundResource(R.drawable.bg_dot_stopped)
                binding.shizukuStatusText.text = "Installed, but Shizuku service is not running"
                binding.shizukuStatusText.setTextColor(activity.getColor(R.color.on_surface_muted))
                binding.btnShizukuAction.text = "Open Shizuku App"
                binding.btnShizukuAction.setBackgroundResource(R.drawable.bg_pill_button_outline)
                binding.btnShizukuAction.setTextColor(activity.getColor(R.color.on_surface))
                binding.btnShizukuAction.isEnabled = true
            }
            else -> {
                binding.shizukuStatusDot.setBackgroundResource(R.drawable.bg_dot_stopped)
                binding.shizukuStatusText.text = "Shizuku app is not installed"
                binding.shizukuStatusText.setTextColor(activity.getColor(R.color.missing))
                binding.btnShizukuAction.text = "Install Shizuku"
                binding.btnShizukuAction.setBackgroundResource(R.drawable.bg_pill_button_cyan)
                binding.btnShizukuAction.setTextColor(activity.getColor(R.color.surface))
                binding.btnShizukuAction.isEnabled = true
            }
        }

        // 2. Root Card UI
        if (isRooted) {
            binding.rootStatusDot.setBackgroundResource(R.drawable.bg_dot_running)
            binding.rootStatusText.text = "Root Access Granted"
            binding.rootStatusText.setTextColor(activity.getColor(R.color.accent_green))
            binding.btnRootAction.text = "Root Active"
            binding.btnRootAction.setBackgroundResource(R.drawable.bg_pill_button_disabled)
            binding.btnRootAction.setTextColor(activity.getColor(R.color.on_surface_muted))
            binding.btnRootAction.isEnabled = false
        } else {
            binding.rootStatusDot.setBackgroundResource(R.drawable.bg_dot_stopped)
            binding.rootStatusText.text = "Root not granted or not detected"
            binding.rootStatusText.setTextColor(activity.getColor(R.color.on_surface_muted))
            binding.btnRootAction.text = "Request Root Access"
            binding.btnRootAction.setBackgroundResource(R.drawable.bg_pill_button_outline)
            binding.btnRootAction.setTextColor(activity.getColor(R.color.on_surface))
            binding.btnRootAction.isEnabled = true
        }

        // 3. Bottom START OVERLAY Button
        binding.btnElevatedStart.isEnabled = true
        binding.btnElevatedStart.text = "START OVERLAY"
        if (hasElevated) {
            binding.btnElevatedStart.setBackgroundResource(R.drawable.bg_pill_button_green)
            binding.btnElevatedStart.setTextColor(activity.getColor(R.color.on_brand_primary))
        } else {
            binding.btnElevatedStart.setBackgroundResource(R.drawable.bg_pill_button_outline)
            binding.btnElevatedStart.setTextColor(activity.getColor(R.color.on_surface))
        }
    }

    private fun setupListeners() {
        // Shizuku Action button
        binding.btnShizukuAction.setOnClickListener {
            val shizuku = ServiceLocator.shizuku
            when {
                shizuku.isBinderAvailable() -> {
                    shizuku.requestPermission { granted ->
                        activity.runOnUiThread {
                            refreshState()
                            if (granted) {
                                ServiceLocator.settings.setPreferredAccessMode(AccessMode.SHIZUKU)
                                ServiceLocator.settings.setPrivilegedPromptSeen(true)
                                ServiceLocator.metrics.refreshCapabilities()
                                Toast.makeText(activity, "Shizuku permission granted!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(activity, "Shizuku permission not granted", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                shizuku.isInstalled() -> {
                    openShizukuApp()
                }
                else -> {
                    openShizukuPlayStore()
                }
            }
        }

        // Toggle guide
        binding.btnToggleGuide.setOnClickListener {
            expandGuide(!isGuideExpanded)
        }

        // Guide direct shortcuts
        binding.btnGuidePlayStore.setOnClickListener { openShizukuPlayStore() }
        binding.btnGuideDevSettings.setOnClickListener { openDeveloperSettings() }
        binding.btnGuideOpenShizuku.setOnClickListener { openShizukuApp() }

        // Root Request button
        binding.btnRootAction.setOnClickListener {
            val owner = activity as? LifecycleOwner
            if (owner != null) {
                binding.btnRootAction.isEnabled = false
                binding.btnRootAction.text = "Requesting root..."
                owner.lifecycleScope.launch(Dispatchers.IO) {
                    val ok = ServiceLocator.metrics.rootShell().requestRoot()
                    withContext(Dispatchers.Main) {
                        refreshState()
                        if (ok) {
                            ServiceLocator.settings.setPreferredAccessMode(AccessMode.ROOT)
                            ServiceLocator.settings.setPrivilegedPromptSeen(true)
                            ServiceLocator.metrics.refreshCapabilities()
                            Toast.makeText(activity, "Superuser access granted!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(activity, "No root privilege detected. Try Shizuku instead.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        // Start overlay
        binding.btnElevatedStart.setOnClickListener {
            ServiceLocator.settings.setPrivilegedPromptSeen(true)
            dialog.dismiss()
            onStartOverlayRequested()
        }

        // Dismiss
        binding.btnElevatedDismiss.setOnClickListener {
            ServiceLocator.settings.setPrivilegedPromptSeen(true)
            dialog.dismiss()
        }

        // Recheck
        binding.btnElevatedRecheck.setOnClickListener {
            refreshState()
            Toast.makeText(activity, "Access status refreshed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun expandGuide(expand: Boolean) {
        isGuideExpanded = expand
        binding.shizukuGuideContainer.visibility = if (expand) View.VISIBLE else View.GONE
        binding.btnToggleGuide.text = if (expand) "Setup Guide ▲" else "Setup Guide ▼"
    }

    private fun openShizukuPlayStore() {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api")))
        } catch (_: Exception) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")))
            } catch (_: Exception) {
                Toast.makeText(activity, "Could not open Play Store", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openShizukuApp() {
        val launchIntent = activity.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (launchIntent != null) {
            activity.startActivity(launchIntent)
        } else {
            openShizukuPlayStore()
        }
    }

    private fun openDeveloperSettings() {
        try {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(activity, "Could not open Settings", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
