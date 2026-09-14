package com.streamvault.app.vpn

import android.content.Context
import android.net.Network
import com.streamvault.player.playback.PlaybackNetworkPolicy
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Response

internal object WireGuardNetworkGuard : Interceptor, PlaybackNetworkPolicy {
    private val mutableChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)
    override val changes: kotlinx.coroutines.flow.Flow<Long> = mutableChanges
    val gate = VpnSocketGate()
    private var required = false
    val protectionEnabled: Boolean @Synchronized get() = required
    private var network: Network? = null
    private var transport: VpnSocketGate.Transport? = null

    @Synchronized fun install(context: Context) {
        setRequired(WireGuardPreferences.get(context).state.value.killSwitch)
    }
    @Synchronized fun setRequired(value: Boolean) {
        val changed = required != value
        required = value
        gate.configure(required, transport)
        if (changed) mutableChanges.value += 1
    }
    @Synchronized fun connected(value: Network?) {
        if (network == value) return
        network = value
        transport = value?.let { VpnSocketGate.Transport(it.socketFactory, vpnDns { host -> it.getAllByName(host).toList() }) }
        gate.configure(required, transport)
        mutableChanges.value += 1
    }
    override fun intercept(chain: Interceptor.Chain): Response {
        gate.check()
        return chain.proceed(chain.request())
    }
    @Synchronized override fun checkPlayback(uri: android.net.Uri) {
        val scheme = uri.scheme?.lowercase()
        if (scheme in setOf("file", "content", "asset", "android.resource", "data")) return
        gate.check()
        if (required && scheme !in setOf("http", "https")) {
            throw java.io.IOException("VPN kill switch: unsupported playback transport")
        }
    }
}
