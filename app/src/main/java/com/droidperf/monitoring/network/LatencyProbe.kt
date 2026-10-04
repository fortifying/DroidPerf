package com.droidperf.monitoring.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * Latency probe using ICMP-style reachability via InetAddress.isReachable, which needs
 * no root on Android (it falls back to a TCP echo on port 7 when raw ICMP is denied).
 *
 * This is only run when the user enables network latency, and only on a slow schedule,
 * so it never becomes a background ping flood.
 */
object LatencyProbe {

    /** Returns round-trip time in ms, or null when the host is unreachable. */
    suspend fun ping(host: String = "8.8.8.8", timeoutMs: Int = 1000): Int? =
        withContext(Dispatchers.IO) {
            try {
                val addr = InetAddress.getByName(host)
                val start = System.nanoTime()
                val ok = addr.isReachable(timeoutMs)
                if (!ok) return@withContext null
                val elapsedMs = ((System.nanoTime() - start) / 1_000_000L).toInt()
                elapsedMs
            } catch (_: Throwable) {
                null
            }
        }
}
