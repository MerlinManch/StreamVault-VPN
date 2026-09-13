package com.streamvault.app.vpn

import android.content.Intent
import com.wireguard.android.backend.GoBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Explicitly started before GoBackend.setState; exposes completion of backend teardown. */
class WireGuardTunnelService : GoBackend.VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) stopSelf()
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        showWireGuardNotification()
        mutableReady.value = true
    }

    override fun onDestroy() {
        super.onDestroy()
        mutableReady.value = false
    }

    companion object {
        private val mutableReady = MutableStateFlow(false)
        internal val ready = mutableReady.asStateFlow()
    }
}
