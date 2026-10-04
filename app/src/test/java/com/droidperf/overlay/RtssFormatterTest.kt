package com.droidperf.overlay

import com.droidperf.domain.Metric
import com.droidperf.domain.MetricsSnapshot
import com.droidperf.settings.OsdStyle
import com.droidperf.settings.OverlayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtssFormatterTest {

    private val fullSnapshot = MetricsSnapshot(
        fps = Metric.Available(90.0),
        frameTimeMs = Metric.Available(11.1),
        fpsAvg = Metric.Available(88.4),
        fpsOnePercentLow = Metric.Available(71.2),
        displayRefreshRateHz = Metric.Available(120f),
        cpuTotalPercent = Metric.Available(33.0),
        cpuFreqMhz = Metric.Available(listOf(1800, 2800, 3200)),
        cpuTempC = Metric.Available(37.0),
        gpuUsagePercent = Metric.Available(93.0),
        gpuFreqMhz = Metric.Available(3015),
        gpuTempC = Metric.Available(71.0),
        gpuMemUsedMb = Metric.Available(3572),
        ramUsedBytes = Metric.Available(13_963L * 1_048_576L),
    )

    @Test
    fun `rows follow the reference layout with grouped multi-column metrics`() {
        val lines = MetricsFormatter.rtss(fullSnapshot, OverlayConfig())
        assertEquals(5, lines.size)

        // Row 1: GPU  71 °C  93 %  3015 MHz
        val gpu = lines[0]
        assertEquals("GPU", gpu.label)
        assertEquals(3, gpu.stats.size)
        assertEquals("71", gpu.stats[0].value)
        assertEquals("°C", gpu.stats[0].unit)
        assertEquals("93", gpu.stats[1].value)
        assertEquals("%", gpu.stats[1].unit)
        assertEquals("3015", gpu.stats[2].value)
        assertEquals("MHz", gpu.stats[2].unit)

        // Row 2: MEM  3572 MB
        val mem = lines[1]
        assertEquals("MEM", mem.label)
        assertEquals(1, mem.stats.size)
        assertEquals("3572", mem.stats[0].value)
        assertEquals("MB", mem.stats[0].unit)

        // Row 3: CPU  37 °C  33 %  3200 MHz
        val cpu = lines[2]
        assertEquals("CPU", cpu.label)
        assertEquals(3, cpu.stats.size)
        assertEquals("37", cpu.stats[0].value)
        assertEquals("°C", cpu.stats[0].unit)
        assertEquals("33", cpu.stats[1].value)
        assertEquals("%", cpu.stats[1].unit)
        assertEquals("3200", cpu.stats[2].value)
        assertEquals("MHz", cpu.stats[2].unit)

        // Row 4: RAM  13963 MB
        val ram = lines[3]
        assertEquals("RAM", ram.label)
        assertEquals(1, ram.stats.size)
        assertEquals("13963", ram.stats[0].value)
        assertEquals("MB", ram.stats[0].unit)

        // Row 5: FPS  90 FPS  11.1 ms
        val fpsRow = lines[4]
        assertEquals("FPS", fpsRow.label)
        assertEquals(2, fpsRow.stats.size)
        assertEquals("90", fpsRow.stats[0].value)
        assertEquals("FPS", fpsRow.stats[0].unit)
        assertEquals("11.1", fpsRow.stats[1].value)
        assertEquals("ms", fpsRow.stats[1].unit)
    }

    @Test
    fun `category and value colors match RTSS signature specification`() {
        val lines = MetricsFormatter.rtss(fullSnapshot, OverlayConfig())
        val gpu = lines.first { it.label == "GPU" }
        val mem = lines.first { it.label == "MEM" }
        val cpu = lines.first { it.label == "CPU" }
        val ram = lines.first { it.label == "RAM" }
        val fpsRow = lines.first { it.label == "FPS" }

        // Labels
        assertEquals(MetricsFormatter.COLOR_RTSS_GPU, gpu.labelColor)
        assertEquals(MetricsFormatter.COLOR_RTSS_GPU, mem.labelColor)
        assertEquals(MetricsFormatter.COLOR_RTSS_CPU, cpu.labelColor)
        assertEquals(MetricsFormatter.COLOR_RTSS_CPU, ram.labelColor)
        assertEquals(MetricsFormatter.COLOR_RTSS_API, fpsRow.labelColor)

        // Hardware stat values are RTSS orange
        gpu.stats.forEach { assertEquals(MetricsFormatter.COLOR_RTSS_STAT, it.color) }
        mem.stats.forEach { assertEquals(MetricsFormatter.COLOR_RTSS_STAT, it.color) }
        cpu.stats.forEach { assertEquals(MetricsFormatter.COLOR_RTSS_STAT, it.color) }
        ram.stats.forEach { assertEquals(MetricsFormatter.COLOR_RTSS_STAT, it.color) }

        // Framerate / Frametime stat values are crisp white
        fpsRow.stats.forEach { assertEquals(MetricsFormatter.COLOR_RTSS_WHITE, it.color) }
    }

    @Test
    fun `unavailable metrics render as gray N A`() {
        val snap = MetricsSnapshot() // all unavailable
        val lines = MetricsFormatter.rtss(snap, OverlayConfig())
        val fpsRow = lines.first { it.label == "FPS" }
        assertEquals("N/A", fpsRow.stats[0].value)
        assertEquals(MetricsFormatter.COLOR_RTSS_NA, fpsRow.stats[0].color)
    }

    @Test
    fun `gpu memory row only appears when the device exposes a counter`() {
        val noMem = fullSnapshot.copy(gpuMemUsedMb = Metric.Unavailable("no GPU memory counter on this device"))
        val lines = MetricsFormatter.rtss(noMem, OverlayConfig())
        assertFalse(lines.any { it.label == "MEM" })
    }

    @Test
    fun `metric group toggles are honored`() {
        val cfg = OverlayConfig(
            showFps = false,
            showCpu = false,
            showGpu = false,
            showTemperature = false,
            showCpuTemp = false,
            showBatteryTemp = false,
            showRam = false,
        )
        val lines = MetricsFormatter.rtss(fullSnapshot, cfg)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `classic style is unaffected by rtss renderer`() {
        val cfg = OverlayConfig(osdStyle = OsdStyle.RTSS, showFps = true)
        // Classic renderer output is unchanged regardless of the selected style.
        val classic = MetricsFormatter.render(fullSnapshot, cfg)
        assertTrue(classic.any { it.startsWith("FPS") })
        assertFalse(classic.any { it.contains("1% Lows") })
    }
}
