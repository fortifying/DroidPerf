package com.droidperf.monitoring.fps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameStatsParserTest {

    @Test
    fun `parses intended vsync rows into fps`() {
        val lines = listOf(
            "Window: com.example.game/com.example.game.MainActivity",
            "FRAMESTATS,PROFILEDATA,",
            "0,INTENDED_VSYNC,1000000000,0",
            "0,FRAME_COMPLETED,1005000000,0",
            "0,INTENDED_VSYNC,1016666666,0",
            "0,INTENDED_VSYNC,1033333332,0",
            "0,INTENDED_VSYNC,1049999998,0",
        )
        val r = FrameStatsParser.parse(lines)!!
        assertEquals(60.0, r.fps, 0.2)
        assertEquals(4, r.frameCount)
    }

    @Test
    fun `returns null when the FRAMESTATS section is absent`() {
        assertNull(FrameStatsParser.parse(listOf("Window: foo", "Total frames rendered: 10")))
    }

    @Test
    fun `returns null when there are not enough frames`() {
        val lines = listOf("FRAMESTATS", "0,INTENDED_VSYNC,1000000000,0")
        assertNull(FrameStatsParser.parse(lines))
    }
}
