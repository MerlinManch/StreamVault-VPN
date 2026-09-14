package com.streamvault.app.vpn

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class WireGuardOptions(
    val autoConnect: Boolean = false,
    val killSwitch: Boolean = false,
    val showStatus: Boolean = false,
    val profileId: String? = null
)

internal class WireGuardPreferences private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("wireguard-options", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(WireGuardOptions(
        prefs.getBoolean("auto", false), prefs.getBoolean("kill", false),
        prefs.getBoolean("status", false), prefs.getString("profile", null)
    ))
    val state = mutableState.asStateFlow()

    @Synchronized fun update(transform: (WireGuardOptions) -> WireGuardOptions) {
        val next = transform(state.value)
        // Close pre-existing direct sockets before the switch can become active.
        WireGuardNetworkGuard.setRequired(next.killSwitch)
        val persisted = prefs.edit().putBoolean("auto", next.autoConnect).putBoolean("kill", next.killSwitch)
            .putBoolean("status", next.showStatus).putString("profile", next.profileId).commit()
        if (!persisted) {
            WireGuardNetworkGuard.setRequired(state.value.killSwitch)
            error("Could not persist VPN options")
        }
        mutableState.value = next
    }

    companion object {
        @Volatile private var instance: WireGuardPreferences? = null
        fun get(context: Context): WireGuardPreferences = instance ?: synchronized(this) {
            instance ?: WireGuardPreferences(context).also { instance = it }
        }
    }
}
