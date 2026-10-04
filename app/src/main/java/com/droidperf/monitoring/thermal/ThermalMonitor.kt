package com.droidperf.monitoring.thermal

import android.content.Context
import android.os.Build
import android.os.HardwarePropertiesManager
import com.droidperf.domain.Metric
import com.droidperf.system.SysFs

/**
 * Thermal monitoring.
 *
 * Two independent sources are probed, in order of reliability:
 *
 *  1. HardwarePropertiesManager: the official API. On most devices it throws
 *     SecurityException for normal apps (it is gated behind DEVICE_POWER), so it is
 *     probed and used only when it genuinely returns data.
 *
 *  2. /sys/class/thermal/thermal_zone*: raw kernel zones. Indices are NOT stable across
 *     devices, so we classify each zone by its "type" label and only report a metric
 *     when a label confidently matches. Unmatched zones are never guessed.
 */
class ThermalMonitor(private val context: Context) {

    data class Temps(
        val cpu: Metric<Double>,
        val gpu: Metric<Double>,
        val device: Metric<Double>,
        val battery: Metric<Double>,
    )

    private data class ZoneEntry(val name: String, val type: String)
    private data class Zone(val type: String, val tempC: Double)

    /** Cache zone name to type mapping once, as sysfs thermal zone types do not change. */
    private val cachedZones: List<ZoneEntry> by lazy {
        val base = "/sys/class/thermal"
        SysFs.listDir(base)
            .filter { it.startsWith("thermal_zone") }
            .mapNotNull { name ->
                val type = SysFs.readText("$base/$name/type") ?: return@mapNotNull null
                ZoneEntry(name, type.lowercase())
            }
    }

    private fun readTemperatures(): List<Zone> {
        val base = "/sys/class/thermal"
        return cachedZones.mapNotNull { entry ->
            val raw = SysFs.readLong("$base/${entry.name}/temp") ?: return@mapNotNull null
            val c = if (raw > 1000) raw / 1000.0 else raw.toDouble()
            if (c in -30.0..150.0) Zone(entry.type, c) else null
        }
    }

    fun sample(): Temps {
        val zones = readTemperatures()

        val cpu = matchZone(zones, listOf("cpu", "soc_thermal", "ap", "big", "little", "cluster"))
        val gpu = matchZone(zones, listOf("gpu", "kgsl", "gpuss", "mali"))
        val battery = matchZone(zones, listOf("batt", "battery"))
        val device = matchZone(zones, listOf("skin", "board", "pa", "quiet", "device"))

        // HardwarePropertiesManager as a cross-check / primary when permitted.
        val hw = hardwarePropertiesTemps()

        return Temps(
            cpu = prefer(hw?.cpu, cpu),
            gpu = prefer(hw?.gpu, gpu),
            device = prefer(hw?.device, device),
            battery = prefer(hw?.battery, battery),
        )
    }

    private fun matchZone(zones: List<Zone>, keywords: List<String>): Metric<Double> {
        // Pick the hottest matching zone; on phones many "cpu" zones exist per cluster.
        val matches = zones.filter { z -> keywords.any { z.type.contains(it) } }
        return matches.maxByOrNull { it.tempC }
            ?.let { Metric.Available(it.tempC) }
            ?: Metric.Unavailable("no thermal zone label matched: ${keywords.joinToString("/")}")
    }

    private fun prefer(a: Double?, b: Metric<Double>): Metric<Double> =
        when {
            a != null -> Metric.Available(a)
            else -> b
        }

    private data class HwTemps(
        val cpu: Double?, val gpu: Double?, val device: Double?, val battery: Double?,
    )

    /**
     * Best-effort official API. Returns null on any failure, including the usual
     * SecurityException for unprivileged apps.
     */
    private fun hardwarePropertiesTemps(): HwTemps? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        return try {
            val hpm = context.getSystemService(Context.HARDWARE_PROPERTIES_SERVICE)
                as? HardwarePropertiesManager ?: return null
            val temps = hpm.getDeviceTemperatures(
                HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU,
                HardwarePropertiesManager.TEMPERATURE_CURRENT,
            )
            val cpu = temps.firstOrNull()?.takeIf { it.isFinite() && it > 0 }?.toDouble()
            HwTemps(cpu = cpu, gpu = null, device = null, battery = null)
        } catch (_: SecurityException) {
            null
        } catch (_: Throwable) {
            null
        }
    }
}
