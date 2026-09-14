package com.streamvault.app.vpn

import android.net.Network
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom

/** A root NS DNS query to the profile's DNS server, always bound to the VPN. */
internal object WireGuardHealthProbe {
    fun check(network: Network, dns: InetAddress): Boolean = runCatching {
        val id = SecureRandom().nextInt(65_536)
        val request = byteArrayOf((id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 1)
        DatagramSocket().use { socket ->
            network.bindSocket(socket)
            socket.soTimeout = 2_000
            socket.connect(dns, 53)
            socket.send(DatagramPacket(request, request.size))
            val response = DatagramPacket(ByteArray(512), 512)
            socket.receive(response)
            response.length >= 12 && response.data[0] == request[0] &&
                response.data[1] == request[1] && (response.data[2].toInt() and 0x80) != 0
        }
    }.getOrDefault(false)
}
