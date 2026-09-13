package com.streamvault.app.vpn

import org.junit.Assert.*
import org.junit.Test
import com.wireguard.crypto.KeyPair

class WireGuardConfigPolicyTest {
    private fun profile(allowed: String = "0.0.0.0/0, ::/0", extra: String = "", dns: String = "DNS = 10.7.0.1") = """
        [Interface]
        PrivateKey = ${KeyPair().privateKey.toBase64()}
        Address = 10.7.0.2/32
        $dns
        $extra
        [Peer]
        PublicKey = ${KeyPair().publicKey.toBase64()}
        Endpoint = 192.0.2.1:51820
        AllowedIPs = $allowed
        PersistentKeepalive = 25
    """.trimIndent()

    @Test fun scopesTunnelToActualBuildPackageAndPreservesPeer() {
        val source = profile(extra = "ExcludedApplications = com.streamvault.app.debug")
        val config = WireGuardConfigPolicy.parse(source, "com.streamvault.app.debug")
        assertEquals(setOf("com.streamvault.app.debug"), config.`interface`.includedApplications)
        assertTrue(config.`interface`.excludedApplications.isEmpty())
        assertEquals(25, config.peers.single().persistentKeepalive.get())
        assertEquals("192.0.2.1", config.peers.single().endpoint.get().host)
    }

    @Test fun importedApplicationListsCannotRouteOtherApps() {
        val config = WireGuardConfigPolicy.parse(profile(extra = "IncludedApplications = com.other.app"), "test.app")
        assertEquals(setOf("test.app"), config.`interface`.includedApplications)
    }

    @Test fun acceptsIpv4OnlyFullTunnel() {
        WireGuardConfigPolicy.parse(profile(allowed = "0.0.0.0/0"), "test.app")
    }

    @Test fun rejectsSplitTunnel() {
        reject(profile(allowed = "10.0.0.0/8"), VpnProfileException.Reason.FULL_TUNNEL)
    }

    @Test fun rejectsIpv6InterfaceWithoutIpv6DefaultRoute() {
        reject(profile(allowed = "0.0.0.0/0", extra = "Address = fd00::2/128"), VpnProfileException.Reason.FULL_TUNNEL)
    }

    @Test fun rejectsMissingDns() {
        reject(profile(dns = ""), VpnProfileException.Reason.DNS)
    }

    @Test fun rejectsMultiplePeers() {
        reject(profile() + "\n[Peer]\nPublicKey = ${KeyPair().publicKey.toBase64()}\nAllowedIPs = 10.0.0.0/8\n",
            VpnProfileException.Reason.FULL_TUNNEL)
    }

    @Test fun rejectsOversizedInput() {
        reject("x".repeat(WireGuardConfigPolicy.MAX_BYTES + 1), VpnProfileException.Reason.TOO_LARGE)
    }

    @Test fun parserFailureDoesNotExposeSecrets() {
        val secret = "secret-do-not-log"
        try {
            WireGuardConfigPolicy.parse("[Interface]\nPrivateKey = $secret", "test.app")
            fail("Expected invalid key")
        } catch (error: VpnProfileException) {
            assertEquals(VpnProfileException.Reason.INVALID, error.reason)
            assertNull(error.message)
            assertNull(error.cause)
        }
    }

    private fun reject(text: String, reason: VpnProfileException.Reason) {
        try { WireGuardConfigPolicy.parse(text, "test.app"); fail("Expected $reason") }
        catch (error: VpnProfileException) { assertEquals(reason, error.reason) }
    }
}
