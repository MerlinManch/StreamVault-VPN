package com.streamvault.app.vpn

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.WeakHashMap
import javax.net.SocketFactory
import okhttp3.Dns

/** Fail-closed gate shared by API, playback, artwork, recordings and downloads. */
internal class VpnSocketGate {
    data class Transport(val sockets: SocketFactory, val dns: Dns)
    private var required = false
    private var transport: Transport? = null
    private val sockets = WeakHashMap<Socket, Boolean>()

    @Synchronized fun configure(killSwitch: Boolean, vpn: Transport?) {
        if (required != killSwitch || transport !== vpn) {
            required = killSwitch
            transport = vpn
            sockets.keys.toList().forEach { runCatching { it.close() } }
            sockets.clear()
        }
    }

    @Synchronized fun check() {
        if (required && transport == null) throw IOException("VPN kill switch: connection blocked")
    }

    val dns = vpnDns { hostname ->
        val delegate = synchronized(this) {
            check()
            if (required) transport!!.dns else Dns.SYSTEM
        }
        delegate.lookup(hostname)
    }

    val socketFactory: SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = synchronized(this@VpnSocketGate) {
            check()
            val factory = if (required) transport!!.sockets else SocketFactory.getDefault()
            factory.createSocket().also { sockets[it] = true }
        }
        override fun createSocket(host: String, port: Int): Socket =
            connect(dns.lookup(host).first(), port, null, 0)
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
            connect(dns.lookup(host).first(), port, local, localPort)
        override fun createSocket(host: InetAddress, port: Int): Socket = connect(host, port, null, 0)
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
            connect(host, port, local, localPort)
        private fun connect(host: InetAddress, port: Int, local: InetAddress?, localPort: Int): Socket {
            val socket = createSocket()
            try {
                if (local != null) socket.bind(InetSocketAddress(local, localPort))
                socket.connect(InetSocketAddress(host, port))
                return socket
            } catch (error: Exception) { socket.close(); throw error }
        }
    }
}

internal fun vpnDns(resolve: (String) -> List<InetAddress>): Dns = object : Dns {
    override fun lookup(hostname: String): List<InetAddress> = resolve(hostname)
}
