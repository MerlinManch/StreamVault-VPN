package com.streamvault.app.vpn

import org.junit.Assert.*
import org.junit.Test

class VpnConnectionHealthTest {
    @Test fun noHandshakeOrReplyNeverCountsAsConnected() {
        assertFalse(VpnConnectionHealth().observe(0, 0, false))
    }
    @Test fun handshakeConfirmsStartupButAnOldHandshakeDoesNotExtendLiveness() {
        val health = VpnConnectionHealth()
        assertTrue(health.observe(0, 1234, false))
        assertTrue(health.observe(29_999, 1234, false))
        assertFalse(health.observe(30_000, 1234, false))
    }
    @Test fun successfulVpnProbeRecoversAfterOutage() {
        val health = VpnConnectionHealth()
        assertTrue(health.observe(0, 1234, false))
        assertFalse(health.observe(30_000, 1234, false))
        assertTrue(health.observe(31_000, 1234, true))
    }
    @Test fun newHandshakeRenewsHealthEvenWithoutDnsReply() {
        val health = VpnConnectionHealth()
        health.observe(0, 1234, false)
        assertTrue(health.observe(30_000, 2345, false))
        assertFalse(health.observe(60_000, 2345, false))
    }
}
