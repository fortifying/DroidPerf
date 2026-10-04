package com.droidperf.monitoring.gpu

import com.droidperf.domain.GpuVendor
import com.droidperf.domain.Metric
import com.droidperf.system.SysFs

/**
 * GPU monitoring, vendor-aware.
 *
 * Utilization is read from vendor sysfs nodes when they exist. When they do not, we
 * report Unavailable. GPU usage is NEVER estimated from FPS, temperature, frequency or
 * CPU: those are different quantities and deriving one from another would be a fake
 * measurement.
 *
 * Known interfaces:
 *  - Adreno / KGSL:  /sys/class/kgsl/kgsl-3d0/gpubusy   ("busy total")
 *                    /sys/class/kgsl/kgsl-3d0/gpuclk
 *  - Generic devfreq: /sys/class/devfreq/<dev>/load or <dev>/gpu_load, and <dev>/cur_freq
 *  - Mali:           devfreq nodes (vendor dependent), no universal busy counter
 */
class GpuMonitor {

    private val whitespaceRegex = Regex("\\s+")

    val vendor: GpuVendor by lazy { detectVendor() }

    private fun detectVendor(): GpuVendor {
        val soc = SysFs.readText("/sys/devices/soc0/machine")
            ?: SysFs.readText("/sys/devices/soc0/soc_id")
        val kgsl = SysFs.exists("/sys/class/kgsl")
        val mali = SysFs.listDir("/sys/class/devfreq").any { it.contains("mali", true) } ||
            SysFs.exists("/sys/class/misc/mali0")
        return when {
            kgsl -> GpuVendor.ADRENO
            mali -> GpuVendor.MALI
            else -> GpuVendor.detect(hardware = soc, renderer = null, socModel = soc)
        }
    }

    /** Best-effort GPU usage percent. Only real vendor counters are trusted. */
    fun sampleUsage(): Metric<Double> {
        val kgsl = SysFs.readText("/sys/class/kgsl/kgsl-3d0/gpubusy")
        if (kgsl != null) {
            val parts = kgsl.trim().split(whitespaceRegex)
            val busy = parts.getOrNull(0)?.toLongOrNull()
            val total = parts.getOrNull(1)?.toLongOrNull()
            if (busy != null && total != null && total > 0) {
                return Metric.Available((busy.toDouble() / total.toDouble() * 100.0).coerceIn(0.0, 100.0))
            }
        }

        // Generic devfreq load nodes: values are usually 0..1000 or 0..100.
        for (dir in SysFs.listDir("/sys/class/devfreq")) {
            val load = SysFs.readLong("/sys/class/devfreq/$dir/load")
                ?: SysFs.readLong("/sys/class/devfreq/$dir/gpu_load")
                ?: continue
            // Normalize: some report 0..100, some 0..1000, some 0..255.
            val pct = when {
                load <= 100 -> load.toDouble()
                load <= 1000 -> load / 10.0
                else -> load / 255.0 * 100.0
            }
            if (pct.isFinite() && pct in 0.0..100.0) return Metric.Available(pct)
        }

        return Metric.Unavailable(
            "Unavailable on this device because no accessible GPU utilization interface was detected."
        )
    }

    /** GPU clock in MHz from kgsl or devfreq. */
    fun sampleFrequencyMhz(): Metric<Int> {
        // Adreno kgsl gpuclk is in Hz.
        SysFs.readLong("/sys/class/kgsl/kgsl-3d0/gpuclk")?.let { hz ->
            return Metric.Available((hz / 1_000_000L).toInt())
        }
        // devfreq cur_freq is in Hz too.
        for (dir in SysFs.listDir("/sys/class/devfreq")) {
            val hz = SysFs.readLong("/sys/class/devfreq/$dir/cur_freq") ?: continue
            if (hz > 0) return Metric.Available((hz / 1_000_000L).toInt())
        }
        return Metric.Unavailable("no GPU frequency node exposed")
    }

    /**
     * GPU memory in use, in MB, from the KGSL per-device counter when the kernel
     * exposes it (`gpu_mem`, reported in KB). Adreno uses unified memory, so there is
     * no separate total/vram figure; when the node is absent the metric stays
     * Unavailable rather than guessing.
     */
    fun sampleMemoryUsedMb(): Metric<Int> {
        val kb = SysFs.readLong("/sys/class/kgsl/kgsl-3d0/gpu_mem") ?: return Metric.Unavailable(
            "no GPU memory counter on this device"
        )
        if (kb < 0) return Metric.Unavailable("no GPU memory counter on this device")
        return Metric.Available((kb / 1024L).toInt())
    }

    /** GPU temperature via thermal zones whose label mentions the GPU. */
    fun sampleTemperatureC(): Metric<Double> {
        val base = "/sys/class/thermal"
        val zones = SysFs.listDir(base).filter { it.startsWith("thermal_zone") }
        var best = Double.NEGATIVE_INFINITY
        var found = false
        for (z in zones) {
            val type = SysFs.readText("$base/$z/type")?.lowercase() ?: continue
            if (!(type.contains("gpu") || type.contains("kgsl") || type.contains("mali"))) continue
            val raw = SysFs.readLong("$base/$z/temp") ?: continue
            val c = if (raw > 1000) raw / 1000.0 else raw.toDouble()
            if (c in -30.0..150.0 && c > best) { best = c; found = true }
        }
        return if (found) Metric.Available(best)
        else Metric.Unavailable("no thermal zone could be confidently matched to the GPU")
    }
}
