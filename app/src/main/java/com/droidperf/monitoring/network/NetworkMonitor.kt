package com.droidperf.monitoring.network

import android.net.TrafficStats
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * Throughput is derived from deltas of the device-wide byte counters.
 * Latency is measured via ICMP-style reachability probe on demand.
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

        if (rx < 0 || tx < 0) return Speed(null, null)

        if (lastRx < 0) {
            lastRx = rx; lastTx = tx; lastAt = now
            return Speed(null, null)
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

/**
 * Latency probe using reachability via InetAddress.isReachable.
 */
object LatencyProbe {

    suspend fun ping(host: String = "8.8.8.8", timeoutMs: Int = 1000): Int? =
        withContext(Dispatchers.IO) {
            try {
                val addr = InetAddress.getByName(host)
                val start = System.nanoTime()
                val ok = addr.isReachable(timeoutMs)
                if (!ok) return@withContext null
                ((System.nanoTime() - start) / 1_000_000L).toInt()
            } catch (_: Throwable) {
                null
            }
        }
}
