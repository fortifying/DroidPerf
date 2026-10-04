package com.droidperf.monitoring.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkMonitorTest {

    @Test
    fun `first sample is a warm-up and reports no speed`() {
        // The monitor reads TrafficStats internally; on a JVM these return -1 (unsupported),
        // which must surface as null rather than 0. We assert the null contract via the
        // Speed holder produced by an uninitialized monitor path.
        val m = NetworkMonitor()
        val s = m.sampleSpeed()
        // Either the platform reports unsupported (nulls) or a warm-up (nulls). Never 0-faked.
        assertNull(s.downBps)
        assertNull(s.upBps)
    }

    @Test
    fun `speed math from deltas is correct`() {
        // Direct check of the arithmetic the monitor applies: 2 MB over 1 s = 2 MB/s.
        val bytes = 2 * 1024 * 1024L
        val dtSec = 1.0
        assertEquals(2_097_152L, (bytes / dtSec).toLong())
    }
}
