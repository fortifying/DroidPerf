package com.droidperf.overlay

import com.droidperf.domain.Metric
import com.droidperf.domain.MetricsSnapshot
import com.droidperf.settings.OverlayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsFormatterTest {

    @Test
    fun `unavailable metrics render as N A never a number`() {
        val snap = MetricsSnapshot() // everything defaults to Unavailable
        val cfg = OverlayConfig(showFps = true, showCpu = true, showGpu = true)
        val lines = MetricsFormatter.render(snap, cfg)
        assertTrue(lines.any { it.startsWith("FPS") && it.contains("N/A") })
        assertTrue(lines.any { it.startsWith("CPU") && it.contains("N/A") })
        assertTrue(lines.any { it.startsWith("GPU") && it.contains("N/A") })
    }

    @Test
    fun `available metrics render their real value`() {
        val snap = MetricsSnapshot(
            fps = Metric.Available(59.8),
            frameTimeMs = Metric.Available(16.7),
            cpuTotalPercent = Metric.Available(34.0),
        )
        val cfg = OverlayConfig(showFps = true, showCpu = true, compactMode = false)
        val lines = MetricsFormatter.render(snap, cfg)
        assertTrue(lines.any { it.contains("59.8") })
        assertTrue(lines.any { it.contains("16.7") })
        assertTrue(lines.any { it.contains("34%") })
    }

    @Test
    fun `compact mode shows grouped short lines`() {
        val snap = MetricsSnapshot(
            fps = Metric.Available(60.0),
            cpuTotalPercent = Metric.Available(24.0),
            gpuUsagePercent = Metric.Available(38.0),
        )
        val cfg = OverlayConfig(compactMode = true, showFps = true, showCpu = true, showGpu = true)
        val lines = MetricsFormatter.render(snap, cfg)
        assertTrue(lines.contains("FPS 60"))
        assertTrue(lines.contains("CPU 24%"))
        assertTrue(lines.contains("GPU 38%"))
    }

    @Test
    fun `byte formatting uses binary units`() {
        assertEquals("1.0G", MetricsFormatter.bytes(1_073_741_824L))
        assertEquals("512M", MetricsFormatter.bytes(512L * 1024 * 1024))
    }

    @Test
    fun `speed formatting switches between KB and MB`() {
        assertTrue(MetricsFormatter.speed(2 * 1024 * 1024L).contains("MB/s"))
        assertTrue(MetricsFormatter.speed(64 * 1024L).contains("KB/s"))
    }

    @Test
    fun `unavailable reasons are surfaced for diagnostics`() {
        val snap = MetricsSnapshot(
            fps = Metric.Unavailable("needs Shizuku"),
            gpuUsagePercent = Metric.Unavailable("no interface"),
        )
        val reasons = MetricsFormatter.unavailableReasons(snap)
        assertEquals("needs Shizuku", reasons["FPS"])
        assertEquals("no interface", reasons["GPU usage"])
    }

    @Test
    fun `ram percentage is only printed when total and used are real`() {
        val withRam = MetricsSnapshot(
            ramUsedBytes = Metric.Available(6_800_000_000L),
            ramTotalBytes = Metric.Available(11_900_000_000L),
        )
        val lines = MetricsFormatter.detailed(withRam, OverlayConfig(showRam = true))
        assertTrue(lines.any { it.startsWith("RAM") && it.contains("/") })
        assertTrue(lines.any { it.contains("%") })

        val noRam = MetricsSnapshot()
        val lines2 = MetricsFormatter.detailed(noRam, OverlayConfig(showRam = true))
        assertFalse(lines2.any { it.contains("%") && it.startsWith("RAM") })
    }
}
