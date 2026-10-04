package com.droidperf.update

data class AppRelease(
    val tagName: String,
    val versionName: String,
    val title: String,
    val changelog: String,
    val htmlUrl: String,
    val downloadUrl: String,
    val apkFileName: String? = null,
    val apkSizeBytes: Long? = null
)
