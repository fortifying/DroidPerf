package com.droidperf.update

import com.droidperf.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    const val RELEASES_PAGE_URL = "https://github.com/fortifying/DroidPerf/releases"
    private const val API_LATEST_URL = "https://api.github.com/repos/fortifying/DroidPerf/releases/latest"
    private const val API_RELEASES_LIST_URL = "https://api.github.com/repos/fortifying/DroidPerf/releases?per_page=1"

    sealed class CheckResult {
        data class UpdateAvailable(val release: AppRelease) : CheckResult()
        data class UpToDate(val currentVersion: String) : CheckResult()
        data class Error(val message: String, val throwable: Throwable? = null) : CheckResult()
    }

    suspend fun checkForUpdates(
        currentVersion: String = BuildConfig.VERSION_NAME
    ): CheckResult = withContext(Dispatchers.IO) {
        try {
            // First attempt: /releases/latest
            val (statusCode, responseBody) = executeHttpGet(API_LATEST_URL)

            val releaseJson = when {
                statusCode == 200 && responseBody != null -> {
                    JSONObject(responseBody)
                }
                statusCode == 404 -> {
                    // /releases/latest returns 404 if there are only pre-releases or no releases yet.
                    val (listCode, listBody) = executeHttpGet(API_RELEASES_LIST_URL)
                    if (listCode == 200 && listBody != null) {
                        val array = JSONArray(listBody)
                        if (array.length() > 0) array.getJSONObject(0) else null
                    } else if (listCode == 404) {
                        null
                    } else if (listCode == 403) {
                        return@withContext CheckResult.Error("GitHub API rate limit reached. Please try again later.")
                    } else {
                        null
                    }
                }
                statusCode == 403 -> {
                    return@withContext CheckResult.Error("GitHub API rate limit reached. Please try again later.")
                }
                else -> {
                    return@withContext CheckResult.Error("GitHub returned HTTP $statusCode")
                }
            }

            if (releaseJson == null) {
                return@withContext CheckResult.UpToDate(currentVersion)
            }

            val release = parseRelease(releaseJson)
            if (isNewerVersion(release.versionName, currentVersion)) {
                CheckResult.UpdateAvailable(release)
            } else {
                CheckResult.UpToDate(currentVersion)
            }
        } catch (e: Exception) {
            val msg = when {
                e is java.net.UnknownHostException -> "No internet connection or cannot resolve GitHub."
                e is java.net.SocketTimeoutException -> "Connection to GitHub timed out."
                else -> e.message ?: "Failed to check for updates."
            }
            CheckResult.Error(msg, e)
        }
    }

    private fun executeHttpGet(urlString: String): Pair<Int, String?> {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "DroidPerf-Android")
            instanceFollowRedirects = true
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        }
        conn.disconnect()
        return Pair(code, body)
    }

    fun parseRelease(json: JSONObject): AppRelease {
        val tagName = json.optString("tag_name", "").trim()
        val title = json.optString("name", tagName).ifBlank { tagName }
        val versionName = extractVersion(tagName, title)
        val changelog = json.optString("body", "").trim()
        val htmlUrl = json.optString("html_url", RELEASES_PAGE_URL)

        var apkUrl: String? = null
        var apkFileName: String? = null
        var apkSize: Long? = null

        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    val downloadUrl = asset.optString("browser_download_url")
                    if (downloadUrl.isNotBlank()) {
                        apkUrl = downloadUrl
                        apkFileName = name
                        apkSize = asset.optLong("size", 0L).takeIf { it > 0 }
                        break
                    }
                }
            }
        }

        val displayTag = if (tagName.equals("release", ignoreCase = true) && title.isNotBlank()) title else tagName

        return AppRelease(
            tagName = displayTag,
            versionName = versionName,
            title = title,
            changelog = changelog,
            htmlUrl = htmlUrl,
            downloadUrl = apkUrl ?: htmlUrl,
            apkFileName = apkFileName,
            apkSizeBytes = apkSize
        )
    }

    fun extractVersion(tag: String, name: String = ""): String {
        val cleanTag = cleanVersion(tag)
        if (cleanTag.any { it.isDigit() }) {
            return cleanTag
        }
        val regex = Regex("""v?(\d+(\.\d+)+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(name)
        if (match != null) {
            return match.groupValues[1]
        }
        return cleanTag
    }

    fun cleanVersion(tag: String): String {
        return tag.trim()
            .removePrefix("v")
            .removePrefix("V")
    }

    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        val cleanRemote = cleanVersion(remoteVersion).split("-", "+")[0].trim()
        val cleanCurrent = cleanVersion(currentVersion).split("-", "+")[0].trim()

        if (cleanRemote.isBlank() || cleanCurrent.isBlank()) return false
        if (cleanRemote == cleanCurrent) return false

        val remoteParts = cleanRemote.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = cleanCurrent.split(".").map { it.toIntOrNull() ?: 0 }

        val length = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until length) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }

        return false
    }
}
