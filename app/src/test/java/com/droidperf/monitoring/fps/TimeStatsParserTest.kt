package com.droidperf.monitoring.fps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Uses real output captured from a HyperOS (Android 15) SurfaceFlinger timestats dump. */
class TimeStatsParserTest {

    private val realDump = listOf(
        "60fps = 258651863ms",
        "120fps = 24488047ms",
        "0fps = 106704440ms",
        "displayRefreshRate = 60 fps",
        "renderRate = 60 fps",
        "displayRefreshRate = 60 fps",
        "renderRate = 60 fps",
        "layerName = none",
        "displayRefreshRate = 60 fps",
        "renderRate = 60 fps",
        "layerName = 431357a SurfaceView[com.mobile.legends/com.moba.unityplugin.MobaGameUnityActivity](BLAST)#220671",
        "averageFPS = 62.500",
        "displayRefreshRate = 60 fps",
        "renderRate = 60 fps",
        "layerName = none",
        "displayRefreshRate = 60 fps",
        "renderRate = 60 fps",
        "layerName = none",
    )

    @Test
    fun parsesFpsFromSurfaceViewLayerOfTargetPackage() {
        val r = TimeStatsParser.parse(realDump, "com.mobile.legends") ?: return assertNull("expected a result", null)
        assertEquals(62.5, r.fps, 0.001)
        assertEquals(
            "431357a SurfaceView[com.mobile.legends/com.moba.unityplugin.MobaGameUnityActivity](BLAST)#220671",
            r.layer,
        )
    }

    @Test
    fun reportsZeroWhenAValidWindowHasNoFramesForTheTargetPackage() {
        val result = TimeStatsParser.parse(realDump, "com.someone.else")!!
        assertEquals(0.0, result.fps, 0.0)
        assertEquals("com.someone.else (no frames in sample window)", result.layer)
    }

    @Test
    fun ignoresNoneLayersAndNonNumericFps() {
        val r = TimeStatsParser.parse(listOf("layerName = none", "averageFPS = 0.000"), "com.pkg")
        assertNull(r)
    }

    @Test
    fun reportsZeroFpsForAnIdleNamedLayer() {
        val r = TimeStatsParser.parse(
            listOf("layerName = SurfaceView[com.pkg/Game]#0", "averageFPS = 0.000"),
            "com.pkg",
        )
        assertEquals(0.0, r?.fps ?: error("expected an idle reading"), 0.0)
    }

    @Test
    fun emptyOutputYieldsNull() {
        assertNull(TimeStatsParser.parse(emptyList(), "com.pkg"))
    }
}
