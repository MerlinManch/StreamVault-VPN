package com.streamvault.app.vpn

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test

class VpnSocketGateTest {
    private class CountingFactory : SocketFactory() {
        var created = 0
        override fun createSocket(): Socket { created++; return Socket() }
        override fun createSocket(h: String, p: Int): Socket = error("Unexpected direct dial")
        override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket = error("Unexpected direct dial")
        override fun createSocket(h: InetAddress, p: Int): Socket = error("Unexpected direct dial")
        override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int): Socket = error("Unexpected direct dial")
    }

    private fun blocked(block: () -> Unit) {
        try { block(); fail("Expected the kill switch to block") } catch (_: IOException) { }
    }

    @Test fun coldStartWithKillSwitchBlocksSocketsAndDns() {
        val gate = VpnSocketGate()
        gate.configure(true, null)
        blocked { gate.socketFactory.createSocket() }
        blocked { gate.dns.lookup("provider.invalid") }
    }

    @Test fun disablingKillSwitchRestoresOrdinarySocketCreation() {
        val gate = VpnSocketGate()
        gate.configure(true, null)
        gate.configure(false, null)
        gate.socketFactory.createSocket().use { assertFalse(it.isClosed) }
    }

    @Test fun enablingKillSwitchClosesPreExistingDirectConnections() {
        val gate = VpnSocketGate()
        val before = gate.socketFactory.createSocket()
        gate.configure(true, null)
        assertTrue(before.isClosed)
        blocked { gate.socketFactory.createSocket() }
    }

    @Test fun protectedSocketsAndDnsUseOnlyTheVpnTransport() {
        val gate = VpnSocketGate()
        val factory = CountingFactory()
        val expected = InetAddress.getByAddress(byteArrayOf(10, 2, 3, 4))
        var dnsCalls = 0
        gate.configure(true, VpnSocketGate.Transport(factory, vpnDns { dnsCalls++; listOf(expected) }))
        gate.socketFactory.createSocket().use { assertEquals(1, factory.created) }
        assertEquals(listOf(expected), gate.dns.lookup("provider.invalid"))
        assertEquals(1, dnsCalls)
    }

    @Test fun vpnLossClosesExistingSocketAndNeverFallsBack() {
        val gate = VpnSocketGate()
        val factory = CountingFactory()
        gate.configure(true, VpnSocketGate.Transport(factory, vpnDns { emptyList() }))
        val socket = gate.socketFactory.createSocket()
        gate.configure(true, null)
        assertTrue(socket.isClosed)
        blocked { gate.socketFactory.createSocket() }
        blocked { gate.dns.lookup("provider.invalid") }
        assertEquals(1, factory.created)
    }

    @Test fun reconnectionUsesNewTransportAndClosesOldPoolSockets() {
        val gate = VpnSocketGate()
        val old = CountingFactory()
        val next = CountingFactory()
        gate.configure(true, VpnSocketGate.Transport(old, vpnDns { emptyList() }))
        val socket = gate.socketFactory.createSocket()
        gate.configure(true, VpnSocketGate.Transport(next, vpnDns { emptyList() }))
        assertTrue(socket.isClosed)
        gate.socketFactory.createSocket().close()
        assertEquals(1, old.created)
        assertEquals(1, next.created)
    }

    @Test fun unchangedHealthDoesNotInterruptTheStream() {
        val gate = VpnSocketGate()
        val transport = VpnSocketGate.Transport(CountingFactory(), vpnDns { emptyList() })
        gate.configure(true, transport)
        gate.socketFactory.createSocket().use {
            gate.configure(true, transport)
            assertFalse(it.isClosed)
        }
    }
}
