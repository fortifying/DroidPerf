package com.droidperf.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricTest {

    @Test
    fun `available carries its value`() {
        val m: Metric<Int> = Metric.Available(42)
        assertTrue(m.isAvailable)
        assertEquals(42, m.valueOrNull())
        assertNull(m.unavailableReason())
    }

    @Test
    fun `unavailable carries its reason and no value`() {
        val m: Metric<Int> = Metric.Unavailable("no sensor")
        assertFalse(m.isAvailable)
        assertNull(m.valueOrNull())
        assertEquals("no sensor", m.unavailableReason())
    }

    @Test
    fun `nullable helper never fabricates a default`() {
        val real: Int? = 7
        assertEquals(7, real.asMetric("missing").valueOrNull())
        val missing: Int? = null
        assertNull(missing.asMetric("missing").valueOrNull())
        assertEquals("missing", missing.asMetric("missing").unavailableReason())
    }
}
