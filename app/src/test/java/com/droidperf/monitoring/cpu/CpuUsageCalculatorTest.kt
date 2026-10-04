package com.droidperf.monitoring.cpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuUsageCalculatorTest {

    private fun ticks(user: Long, system: Long, idle: Long, iowait: Long = 0): CpuTicks =
        CpuTicks(user = user, nice = 0, system = system, idle = idle, iowait = iowait,
            irq = 0, softirq = 0, steal = 0)

    @Test
    fun `parses a cpu line and the aggregate header`() {
        val (label, t) = CpuTicks.parse("cpu0 100 2 50 800 10 0 5 0 0 0")!!
        assertEquals("cpu0", label)
        assertEquals(100L, t.user)
        assertEquals(800L, t.idle)
        assertEquals(10L, t.iowait)
        assertEquals(967L, t.total)

        val (agg, _) = CpuTicks.parse("cpu  200 0 100 1600 0 0 0 0 0 0")!!
        assertEquals("cpu", agg)
    }

    @Test
    fun `rejects malformed lines instead of inventing a core`() {
        assertNull(CpuTicks.parse("notacpu 1 2 3"))
        assertNull(CpuTicks.parse("cpu0 1 2"))
    }

    @Test
    fun `computes 50 percent busy from a clean delta`() {
        val prev = ticks(user = 0, system = 0, idle = 0)
        // 50 busy jiffies, 50 idle jiffies over the window.
        val curr = ticks(user = 40, system = 10, idle = 50)
        assertEquals(50.0, CpuUsageCalculator.usage(prev, curr)!!, 0.001)
    }

    @Test
    fun `counts iowait as idle, not busy`() {
        val prev = ticks(user = 0, system = 0, idle = 0, iowait = 0)
        val curr = ticks(user = 0, system = 0, idle = 0, iowait = 100)
        assertEquals(0.0, CpuUsageCalculator.usage(prev, curr)!!, 0.001)
    }

    @Test
    fun `returns null when no time elapsed rather than dividing by zero`() {
        val prev = ticks(10, 10, 10)
        assertNull(CpuUsageCalculator.usage(prev, prev))
    }

    @Test
    fun `aggregate uses the cpu summary line`() {
        val prev = mapOf("cpu" to ticks(0, 0, 0), "cpu0" to ticks(0, 0, 0))
        val curr = mapOf("cpu" to ticks(30, 30, 40), "cpu0" to ticks(30, 30, 40))
        assertEquals(60.0, CpuUsageCalculator.aggregate(prev, curr)!!, 0.001)
    }

    @Test
    fun `per-core is sorted numerically and excludes the aggregate`() {
        val prev = mapOf(
            "cpu" to ticks(0, 0, 0),
            "cpu1" to ticks(0, 0, 0),
            "cpu0" to ticks(0, 0, 0),
        )
        val curr = mapOf(
            "cpu" to ticks(0, 0, 100),
            "cpu1" to ticks(0, 0, 100),
            "cpu0" to ticks(100, 0, 0),
        )
        val cores = CpuUsageCalculator.perCore(prev, curr)
        assertEquals(2, cores.size)
        assertEquals(100.0, cores[0], 0.001) // cpu0 busy
        assertEquals(0.0, cores[1], 0.001)   // cpu1 idle
    }

    @Test
    fun `clamps to 0 to 100 even with counter weirdness`() {
        val prev = ticks(0, 0, 0)
        val curr = ticks(0, 0, 500)
        val v = CpuUsageCalculator.usage(prev, curr)!!
        assertTrue(v in 0.0..100.0)
    }
}
