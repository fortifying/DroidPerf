package com.droidperf.monitoring.fps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FpsStatsTrackerTest {

    @Test
    fun averageOverKnownSamples() {
        val t = FpsStatsTracker()
        listOf(60.0, 62.0, 64.0).forEach(t::feed)
        assertEquals(62.0, t.average()!!, 0.0001)
    }

    @Test
    fun averageIsNullBeforeMinimumSamples() {
        val t = FpsStatsTracker()
        t.feed(60.0); t.feed(61.0)
        assertNull(t.average())
    }

    @Test
    fun onePercentLowIsAverageOfSlowestOnePercent() {
        val t = FpsStatsTracker()
        // 99 samples at ~90 fps + 1 bad sample at 10 fps (1% of 100 = 1 sample).
        repeat(99) { t.feed(90.0) }
        t.feed(10.0)
        assertEquals(10.0, t.onePercentLow()!!, 0.0001)
        assertEquals(89.2, t.average()!!, 0.0001)
    }

    @Test
    fun onePercentLowNeedsHistory() {
        val t = FpsStatsTracker()
        repeat(10) { t.feed(60.0) }
        assertNull(t.onePercentLow())
    }

    @Test
    fun rollingWindowDropsOldestSamples() {
        val t = FpsStatsTracker(maxSamples = 100)
        repeat(100) { t.feed(100.0) }
        repeat(100) { t.feed(50.0) }   // pushes all 100s out of the window
        assertEquals(50.0, t.average()!!, 0.0001)
    }

    @Test
    fun clearResetsHistory() {
        val t = FpsStatsTracker()
        repeat(50) { t.feed(60.0) }
        t.clear()
        assertNull(t.average())
        assertNull(t.onePercentLow())
    }

    @Test
    fun nonPositiveSamplesAreIgnored() {
        val t = FpsStatsTracker()
        t.feed(0.0); t.feed(-5.0); t.feed(Double.NaN); t.feed(60.0)
        assertEquals(1, t.sampleCount())
    }
}
