package com.streamvault.app.vpn

/** Monotonic liveness window; wall-clock changes cannot extend connectivity. */
internal class VpnConnectionHealth {
    private var lastHealthy: Long? = null
    private var handshake = 0L
    fun observe(now: Long, handshakeAt: Long, probeSucceeded: Boolean): Boolean {
        if (probeSucceeded || handshakeAt > handshake) lastHealthy = now
        handshake = maxOf(handshake, handshakeAt)
        return lastHealthy?.let { now >= it && now - it < 30_000L } == true
    }
}
