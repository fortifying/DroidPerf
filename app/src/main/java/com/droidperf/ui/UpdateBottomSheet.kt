package com.droidperf.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.droidperf.BuildConfig
import com.droidperf.R
import com.droidperf.databinding.BottomSheetUpdateBinding
import com.droidperf.update.AppRelease
import com.droidperf.update.MarkdownFormatter
import java.util.Locale

class UpdateBottomSheet(
    private val activity: Activity,
    private val release: AppRelease
) {

    private val dialog = BottomSheetDialog(activity, R.style.ThemeOverlay_PerfOverlay_BottomSheetDialog)
    private val binding = BottomSheetUpdateBinding.inflate(LayoutInflater.from(activity))

    init {
        dialog.setContentView(binding.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        bindReleaseData()
        setupListeners()
    }

    private fun bindReleaseData() {
        val curVer = BuildConfig.VERSION_NAME
        val targetVer = if (release.tagName.startsWith("v", ignoreCase = true)) {
            release.tagName
        } else {
            "v${release.tagName}"
        }
        binding.textVersionBadge.text = "v$curVer \u2192 $targetVer"

        binding.textReleaseTitle.text = release.title.ifBlank { "DroidPerf $targetVer" }

        binding.textChangelog.text = MarkdownFormatter.format(release.changelog)

        val sizeBytes = release.apkSizeBytes
        if (sizeBytes != null && sizeBytes > 0) {
            val mb = sizeBytes / (1024.0 * 1024.0)
            binding.textApkSize.text = String.format(Locale.US, "\u2022 %.1f MB", mb)
            binding.textApkSize.visibility = View.VISIBLE
        } else {
            binding.textApkSize.visibility = View.GONE
        }
    }

    private fun setupListeners() {
        binding.btnDismiss.setOnClickListener {
            dialog.dismiss()
        }

        binding.btnDownload.setOnClickListener {
            openInBrowser(release.downloadUrl)
            dialog.dismiss()
        }

        binding.btnViewOnGithub.setOnClickListener {
            openInBrowser(release.htmlUrl)
        }
    }

    private fun openInBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            activity.startActivity(fallback)
        }
    }

    fun show() {
        if (!activity.isFinishing && !activity.isDestroyed) {
            dialog.show()
        }
    }

    fun dismiss() {
        dialog.dismiss()
    }
}
