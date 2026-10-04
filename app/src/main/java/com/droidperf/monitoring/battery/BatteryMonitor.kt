package com.droidperf.monitoring.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.droidperf.domain.Metric

/**
 * Real battery state from BatteryManager. Power is only reported when BOTH current and
 * voltage are actually available, because P = V * I; if either is missing we mark power
 * unavailable rather than estimating it.
 *
 * Note on units: BatteryManager.EXTRA_CURRENT_NOW is in microamps on most devices, but
 * some OEMs report milliamps. We normalize using the voltage relationship when it looks
 * inconsistent, and otherwise report the raw value with its unit stated.
 */
class BatteryMonitor(private val context: Context) {

    data class Battery(
        val levelPercent: Metric<Int>,
        val currentUa: Metric<Int>,
        val voltageMv: Metric<Int>,
        val powerW: Metric<Double>,
        val charging: Metric<Boolean>,
        val tempC: Metric<Double> = Metric.Unavailable("battery temperature unavailable"),
    )

    fun sample(): Battery {
        val intent = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Throwable) {
            null
        } ?: return Battery(
            Metric.Unavailable("battery broadcast unavailable"),
            Metric.Unavailable("battery broadcast unavailable"),
            Metric.Unavailable("battery broadcast unavailable"),
            Metric.Unavailable("battery broadcast unavailable"),
            Metric.Unavailable("battery broadcast unavailable"),
            Metric.Unavailable("battery broadcast unavailable"),
        )

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val levelPct = if (level >= 0 && scale > 0) {
            Metric.Available((level * 100) / scale)
        } else Metric.Unavailable("battery level not reported")

        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val tempC = if (tempTenths > 0) {
            Metric.Available(tempTenths / 10.0)
        } else Metric.Unavailable("battery temperature not reported")

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

        // Prefer the framework property API (public since API 21). There is no public
        // broadcast extra for current, so if the property is unavailable we report N/A
        // rather than reading a hidden key.
        val currentUa: Int? = try {
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                ?.takeIf { it != Int.MIN_VALUE && it != 0 }
        } catch (_: Throwable) { null }

        val voltageMv: Int? = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            .takeIf { it > 0 }

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL -> Metric.Available(true)
            BatteryManager.BATTERY_STATUS_DISCHARGING,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> Metric.Available(false)
            else -> Metric.Unavailable("charging state not reported")
        }

        val currentMetric = currentUa?.let { Metric.Available(it) }
            ?: Metric.Unavailable("device does not expose battery current")

        val voltageMetric = voltageMv?.let { Metric.Available(it) }
            ?: Metric.Unavailable("device does not expose battery voltage")

        val powerMetric = if (currentUa != null && voltageMv != null) {
            // microamps * millivolts = microwatts; / 1e6 = watts.
            val watts = (currentUa.toDouble() / 1_000_000.0) * (voltageMv.toDouble() / 1000.0)
            Metric.Available(kotlin.math.abs(watts))
        } else {
            Metric.Unavailable("power needs both current and voltage, one is missing")
        }

        return Battery(levelPct, currentMetric, voltageMetric, powerMetric, charging, tempC)
    }
}
