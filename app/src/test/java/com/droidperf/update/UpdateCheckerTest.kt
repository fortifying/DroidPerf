package com.droidperf.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun testVersionComparison_newerVersions() {
        assertTrue(UpdateChecker.isNewerVersion("v1.0.1", "1.0.0"))
        assertTrue(UpdateChecker.isNewerVersion("1.0.1", "1.0.0"))
        assertTrue(UpdateChecker.isNewerVersion("v1.1.0", "1.0.0"))
        assertTrue(UpdateChecker.isNewerVersion("v2.0.0", "1.9.9"))
        assertTrue(UpdateChecker.isNewerVersion("v1.0.0.1", "1.0.0"))
        assertTrue(UpdateChecker.isNewerVersion("v1.1.0-alpha", "1.0.0"))
    }

    @Test
    fun testVersionComparison_olderOrEqualVersions() {
        assertFalse(UpdateChecker.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewerVersion("v0.9.9", "1.0.0"))
        assertFalse(UpdateChecker.isNewerVersion("v0.1.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.0.0-rc1", "1.0.0"))
        assertFalse(UpdateChecker.isNewerVersion("", "1.0.0"))
    }

    @Test
    fun testParseRelease_withApkAsset() {
        val json = JSONObject().apply {
            put("tag_name", "v1.0.1")
            put("name", "DroidPerf v1.0.1")
            put("body", "## Features\n* App update support")
            put("html_url", "https://github.com/fortifying/DroidPerf/releases/tag/v1.0.1")

            val assets = JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "DroidPerf-v1.0.1.apk")
                    put("size", 5242880L)
                    put("browser_download_url", "https://github.com/fortifying/DroidPerf/releases/download/v1.0.1/DroidPerf-v1.0.1.apk")
                })
            }
            put("assets", assets)
        }

        val release = UpdateChecker.parseRelease(json)

        assertEquals("v1.0.1", release.tagName)
        assertEquals("1.0.1", release.versionName)
        assertEquals("DroidPerf v1.0.1", release.title)
        assertEquals("https://github.com/fortifying/DroidPerf/releases/download/v1.0.1/DroidPerf-v1.0.1.apk", release.downloadUrl)
        assertEquals("DroidPerf-v1.0.1.apk", release.apkFileName)
        assertEquals(5242880L, release.apkSizeBytes)
    }

    @Test
    fun testParseRelease_withoutApkAsset_fallsBackToHtmlUrl() {
        val json = JSONObject().apply {
            put("tag_name", "v1.0.2")
            put("html_url", "https://github.com/fortifying/DroidPerf/releases/tag/v1.0.2")
            put("assets", JSONArray())
        }

        val release = UpdateChecker.parseRelease(json)

        assertEquals("v1.0.2", release.tagName)
        assertEquals("1.0.2", release.versionName)
        assertEquals("https://github.com/fortifying/DroidPerf/releases/tag/v1.0.2", release.downloadUrl)
    }

    @Test
    fun testParseRelease_withGenericReleaseTagAndVersionInName() {
        val json = JSONObject().apply {
            put("tag_name", "release")
            put("name", "v1.0.0")
            put("body", "Initial release")
            put("html_url", "https://github.com/fortifying/DroidPerf/releases/tag/release")
        }

        val release = UpdateChecker.parseRelease(json)

        assertEquals("v1.0.0", release.tagName)
        assertEquals("1.0.0", release.versionName)
        assertFalse(UpdateChecker.isNewerVersion(release.versionName, "1.0.0"))
        assertTrue(UpdateChecker.isNewerVersion("1.0.1", release.versionName))
    }
}
