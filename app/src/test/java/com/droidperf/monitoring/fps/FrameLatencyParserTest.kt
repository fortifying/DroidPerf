package com.droidperf.monitoring.fps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameLatencyParserTest {

    private val NO_FRAME = "9223372036854775807"

    @Test
    fun `computes fps from real presented timestamps`() {
        // 60 Hz: period 16,666,666 ns. Five frames spaced 16.666ms apart => ~60 fps.
        val out = listOf(
            "16666666",
            "0 $NO_FRAME 0",
            "0 1000000000 0",
            "0 1016666666 0",
            "0 1033333332 0",
            "0 1049999998 0",
            "0 1066666664 0",
        )
        val r = FrameLatencyParser.parse(out)!!
        assertEquals(60.0, r.fps, 0.1)
        assertEquals(16.666, r.frameTimeMs, 0.05)
        assertEquals(5, r.frameCount)
        assertEquals(60.0, r.refreshRateHz!!, 0.1)
    }

    @Test
    fun `ignores no-frame sentinels and zero rows`() {
        val out = listOf(
            "16666666",
            "0 $NO_FRAME 0",
            "0 0 0",
            "0 1000000000 0",
            "0 $NO_FRAME 0",
            "0 1016666666 0",
        )
        val r = FrameLatencyParser.parse(out)!!
        assertEquals(2, r.frameCount)
        assertEquals(60.0, r.fps, 0.2)
    }

    @Test
    fun `returns null when fewer than two frames were presented`() {
        val out = listOf("16666666", "0 $NO_FRAME 0", "0 1000000000 0")
        assertNull(FrameLatencyParser.parse(out))
    }

    @Test
    fun `reports zero fps for a valid window with no presented frames`() {
        val out = listOf("16666666", "0 $NO_FRAME 0", "0 0 0")
        val result = FrameLatencyParser.parse(out)!!
        assertEquals(0.0, result.fps, 0.0)
        assertEquals(0.0, result.frameTimeMs, 0.0)
        assertEquals(0, result.frameCount)
    }

    @Test
    fun `does not treat a header-only response as idle`() {
        assertNull(FrameLatencyParser.parse(listOf("16666666")))
    }

    @Test
    fun `returns null for empty output`() {
        assertNull(FrameLatencyParser.parse(emptyList()))
    }

    @Test
    fun `refresh rate is optional and never invented`() {
        val out = listOf("0", "0 1000000000 0", "0 1016666666 0")
        val r = FrameLatencyParser.parse(out)
        assertNotNull(r)
        assertNull(r!!.refreshRateHz)
    }

    @Test
    fun `handles a 120hz stream`() {
        val period = 8_333_333L
        val rows = (0..4).map { i -> "0 ${1_000_000_000L + i * period} 0" }
        val r = FrameLatencyParser.parse(listOf(period.toString()) + rows)!!
        assertTrue(r.fps in 119.0..121.0)
    }
}
