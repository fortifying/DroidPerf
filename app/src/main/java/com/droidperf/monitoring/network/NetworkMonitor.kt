package com.droidperf.monitoring.network

import android.net.TrafficStats
import android.os.SystemClock

/**
 * Throughput is derived from deltas of the device-wide byte counters, never from a
 * single snapshot. Latency is measured on demand (not continuously) so the monitor
 * does not hammer the network; see [LatencyProbe].
 */
class NetworkMonitor {

    private var lastRx = -1L
    private var lastTx = -1L
    private var lastAt = 0L

    data class Speed(val downBps: Long?, val upBps: Long?)

    fun sampleSpeed(): Speed {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val now = SystemClock.elapsedRealtime()

        if (rx < 0 || tx < 0) return Speed(null, null) // device does not support counters

        if (lastRx < 0) {
            lastRx = rx; lastTx = tx; lastAt = now
            return Speed(null, null) // warm-up
        }

        val dtMs = now - lastAt
        if (dtMs <= 0) return Speed(null, null)

        val dRx = (rx - lastRx).coerceAtLeast(0)
        val dTx = (tx - lastTx).coerceAtLeast(0)

        lastRx = rx; lastTx = tx; lastAt = now

        val dtSec = dtMs / 1000.0
        return Speed(
            downBps = (dRx / dtSec).toLong(),
            upBps = (dTx / dtSec).toLong(),
        )
    }
}
