package com.streamvault.app.vpn

import com.wireguard.config.Config
import com.wireguard.config.Interface
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.Inet6Address

/** Never display parser exception messages: they can contain private keys. */
internal class VpnProfileException(val reason: Reason) : Exception() {
    enum class Reason { INVALID, FULL_TUNNEL, DNS, TOO_LARGE, STORAGE }
}

internal object WireGuardConfigPolicy {
    const val MAX_BYTES = 65_536

    fun parse(text: String, packageName: String): Config {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            throw VpnProfileException(VpnProfileException.Reason.TOO_LARGE)
        }
        // Android application selection is owned by this app, not by an imported profile.
        val sanitized = text.removePrefix("\uFEFF").lineSequence().filterNot {
            val key = it.substringBefore('=').trim()
            key.equals("IncludedApplications", true) || key.equals("ExcludedApplications", true)
        }.joinToString("\n")
        val source = try {
            Config.parse(ByteArrayInputStream(sanitized.toByteArray(Charsets.UTF_8)))
        } catch (_: Exception) {
            throw VpnProfileException(VpnProfileException.Reason.INVALID)
        }
        val iface = source.`interface`
        val peer = source.peers.singleOrNull()
            ?: throw VpnProfileException(VpnProfileException.Reason.FULL_TUNNEL)
        if (iface.addresses.isEmpty() || !peer.endpoint.isPresent) {
            throw VpnProfileException(VpnProfileException.Reason.INVALID)
        }
        if (iface.dnsServers.isEmpty()) throw VpnProfileException(VpnProfileException.Reason.DNS)
        val hasV4Default = peer.allowedIps.any { it.mask == 0 && it.address is Inet4Address }
        val hasV6Default = peer.allowedIps.any { it.mask == 0 && it.address is Inet6Address }
        val usesV6 = iface.addresses.any { it.address is Inet6Address } ||
            iface.dnsServers.any { it is Inet6Address } || peer.allowedIps.any { it.address is Inet6Address }
        if (!hasV4Default || (usesV6 && !hasV6Default)) {
            throw VpnProfileException(VpnProfileException.Reason.FULL_TUNNEL)
        }
        val scoped = Interface.Builder()
            .setKeyPair(iface.keyPair)
            .addAddresses(iface.addresses)
            .addDnsServers(iface.dnsServers)
            .addDnsSearchDomains(iface.dnsSearchDomains)
            .includeApplication(packageName)
        iface.mtu.ifPresent { scoped.setMtu(it) }
        iface.listenPort.ifPresent { scoped.setListenPort(it) }
        return Config.Builder().setInterface(scoped.build()).addPeer(peer).build()
    }
}
