package com.droidperf.monitoring.thermal

import android.content.Context
import android.os.Build
import android.os.HardwarePropertiesManager
import com.droidperf.domain.Metric
import com.droidperf.system.SysFs
import com.droidperf.system.shell.SafeCommands
import com.droidperf.system.shell.Shell
import java.util.regex.Pattern

/**
 * Thermal monitoring across all Android devices.
 *
 * Three independent sources are probed, in order of reliability:
 *
 *  1. Android Thermal HAL service (`dumpsys thermalservice`) via privileged shell (Android 10+).
 *     Provides official normalized thermal telemetry across CPU, GPU, Battery, and Skin.
 *
 *  2. HardwarePropertiesManager: the official Android framework API.
 *     Used when permitted without SecurityException.
 *
 *  3. /sys/class/thermal/thermal_zone*: raw kernel thermal zones.
 *     Classifies zones by type label (SoC, CPU, GPU, Battery, Device) and filters out
 *     Qualcomm mitigation step trip points and PMIC/charge readings.
 */
class ThermalMonitor(
    private val context: Context,
    private val shellProvider: () -> Shell? = { null },
) {

    data class Temps(
        val cpu: Metric<Double>,
        val gpu: Metric<Double>,
        val device: Metric<Double>,
        val battery: Metric<Double>,
    )

    private data class ZoneEntry(val name: String, val type: String)
    private data class Zone(val type: String, val tempC: Double)
    private data class HwTemps(
        val cpu: Double?, val gpu: Double?, val device: Double?, val battery: Double?,
    )

    companion object {
        // Pattern from Android Thermal HAL:
        // Temperature{mValue=42.5, mType=0, mName=cpu0-gold-usr, mStatus=0}
        private val THERMAL_SERVICE_PATTERN = Pattern.compile(
            """Temperature\{mValue=([0-9.]+),\s*mType=(\d+),\s*mName=([^,}]+)(?:,\s*mStatus=(\d+))?"""
        )

        private const val TYPE_CPU = 0
        private const val TYPE_GPU = 1
        private const val TYPE_BATTERY = 2
        private const val TYPE_SKIN = 3
        private const val TYPE_SOC = 10
        private const val TYPE_SOC_AIDL = 13
    }

    @Volatile
    private var cachedZones: List<ZoneEntry>? = null

    private fun getZones(): List<ZoneEntry> {
        val current = cachedZones
        if (!current.isNullOrEmpty()) return current
        val base = "/sys/class/thermal"
        val zones = SysFs.listDir(base)
            .filter { it.startsWith("thermal_zone") }
            .mapNotNull { name ->
                val type = SysFs.readText("$base/$name/type")?.lowercase()?.trim() ?: return@mapNotNull null
                if (isExcludedZone(type)) return@mapNotNull null
                ZoneEntry(name, type)
            }
        if (zones.isNotEmpty()) {
            cachedZones = zones
        }
        return zones
    }

    private fun isExcludedZone(type: String): Boolean {
        // Exclude Qualcomm trip point step files (e.g. cpu-1-step, gpu-step)
        if (type.endsWith("-step") || type.contains("-step-") || type.contains("avg-step") || type.contains("max-step")) return true
        // Standalone "soc" without thermal/max/temp is battery State of Charge (%)
        if (type == "soc" || type == "soc-step" || (type.startsWith("soc") && !type.contains("thermal") && !type.contains("max") && !type.contains("temp") && !type.contains("cpu"))) return true
        // Exclude battery charging & PMIC limits
        if (type.contains("pmic") || type.contains("vbat") || type.contains("ibat") || type.contains("bcl")) return true
        return false
    }

    private fun readTemperatures(): List<Zone> {
        val base = "/sys/class/thermal"
        return getZones().mapNotNull { entry ->
            val raw = SysFs.readLong("$base/${entry.name}/temp") ?: return@mapNotNull null
            val c = if (raw > 1000) raw / 1000.0 else raw.toDouble()
            if (c in -30.0..150.0) Zone(entry.type, c) else null
        }
    }

    fun sample(): Temps {
        val halTemps = readThermalHal()
        val hw = hardwarePropertiesTemps()
        val zones = readTemperatures()
        val cpuZone = matchZone(zones, listOf("cpu", "soc_max", "soc_thermal", "soc-thermal", "ap", "ap_thermal", "tsens", "mtktscpu", "mtktsap", "cluster", "cortex", "kryo", "big", "little"))
        val gpuZone = matchZone(zones, listOf("gpu", "kgsl", "gpuss", "mali", "sgpu", "g3d"))
        val batteryZone = matchZone(zones, listOf("batt", "battery", "bms"))
        val deviceZone = matchZone(zones, listOf("skin", "board", "pa", "quiet", "device"))

        return Temps(
            cpu = prefer(halTemps?.cpu, prefer(hw?.cpu, cpuZone)),
            gpu = prefer(halTemps?.gpu, prefer(hw?.gpu, gpuZone)),
            device = prefer(halTemps?.device, prefer(hw?.device, deviceZone)),
            battery = prefer(halTemps?.battery, prefer(hw?.battery, batteryZone)),
        )
    }

    private fun readThermalHal(): HwTemps? {
        val shell = shellProvider() ?: return null
        val res = shell.exec(SafeCommands.THERMAL_SERVICE) ?: return null
        if (!res.ok || res.stdout.isEmpty()) return null

        val text = res.stdout.joinToString("\n")
        val targetText = if (text.contains("Current temperatures from HAL:")) {
            text.substringAfter("Current temperatures from HAL:")
                .substringBefore("Current cooling devices from HAL:")
                .substringBefore("Temperature static thresholds")
        } else {
            text
        }

        val matcher = THERMAL_SERVICE_PATTERN.matcher(targetText)
        val cpuTemps = mutableListOf<Double>()
        val gpuTemps = mutableListOf<Double>()
        val batTemps = mutableListOf<Double>()
        val skinTemps = mutableListOf<Double>()
        val socTemps = mutableListOf<Double>()

        while (matcher.find()) {
            val v = matcher.group(1)?.toDoubleOrNull() ?: continue
            val type = matcher.group(2)?.toIntOrNull() ?: -1
            val name = (matcher.group(3) ?: "").trim().lowercase()
            val status = matcher.group(4)?.toIntOrNull() ?: 0

            if (status == 4 || v !in 20.0..115.0) continue
            if (isExcludedZone(name)) continue

            when {
                type == TYPE_CPU || name.contains("cpu") || name.contains("gold") || name.contains("silver") || name.contains("kryo") -> cpuTemps.add(v)
                type == TYPE_GPU || name.contains("gpu") || name.contains("gpuss") -> gpuTemps.add(v)
                type == TYPE_BATTERY || name.contains("battery") || name.contains("batt") -> batTemps.add(v)
                type == TYPE_SKIN || name.contains("skin") || name.contains("quiet") -> skinTemps.add(v)
                type == TYPE_SOC || type == TYPE_SOC_AIDL || name.contains("soc") || name.contains("ap") -> socTemps.add(v)
            }
        }

        val cpu = cpuTemps.maxOrNull() ?: socTemps.maxOrNull()
        val gpu = gpuTemps.maxOrNull()
        val bat = batTemps.firstOrNull()
        val skin = skinTemps.maxOrNull()

        if (cpu == null && gpu == null && bat == null && skin == null) return null
        return HwTemps(cpu = cpu, gpu = gpu, device = skin, battery = bat)
    }

    private fun matchZone(zones: List<Zone>, keywords: List<String>): Metric<Double> {
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
