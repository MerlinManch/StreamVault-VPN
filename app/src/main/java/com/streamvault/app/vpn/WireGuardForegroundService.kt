package com.streamvault.app.vpn

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class VpnPhase { OFF, CONNECTING, UP, DISCONNECTING, ERROR }
internal data class VpnStatus(
    val phase: VpnPhase = VpnPhase.OFF,
    val profileId: String? = null,
    val handshakeAt: Long = 0L
) {
    val occupied: Boolean get() = phase in setOf(VpnPhase.CONNECTING, VpnPhase.UP, VpnPhase.DISCONNECTING)
}

/** Keeps the userspace tunnel alive while browsing, playing or recording in the background. */
class WireGuardForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var backend: GoBackend? = null
    @Volatile private var destroyed = false
    @Volatile private var failed = false
    private var started = false
    private val tunnel = object : Tunnel {
        override fun getName() = "streamvault"
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN && status.value.phase == VpnPhase.UP) {
                mutableStatus.value = VpnStatus(VpnPhase.DISCONNECTING)
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        showWireGuardNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DISCONNECT) {
            mutableStatus.update { it.copy(phase = VpnPhase.DISCONNECTING) }
            stopSelf()
        } else if (!started) {
            started = true
            val id = intent?.getStringExtra(PROFILE_ID)
            if (id == null) {
                stopSelf()
            } else {
                mutableStatus.value = VpnStatus(VpnPhase.CONNECTING, id)
                scope.launch {
                    mutex.withLock {
                        try {
                            val config = WireGuardProfileStore(applicationContext).config(id)
                            if (destroyed) return@withLock
                            val engine = GoBackend(applicationContext).also { backend = it }
                            // Populate GoBackend's service future before setState. Keep the
                            // service lifecycle observable so a quick reconnect cannot reuse
                            // a VPN service that is still being destroyed.
                            startService(Intent(this@WireGuardForegroundService, WireGuardTunnelService::class.java))
                            withTimeout(5_000) { WireGuardTunnelService.ready.first { it } }
                            if (destroyed) return@withLock
                            engine.setState(tunnel, Tunnel.State.UP, config)
                            if (!destroyed) mutableStatus.value = VpnStatus(VpnPhase.UP, id)
                        } catch (_: Exception) {
                            failed = true
                            mutableStatus.value = VpnStatus(VpnPhase.DISCONNECTING, id)
                            stopSelf()
                        } catch (_: LinkageError) {
                            // Unsupported/missing native ABI must not crash the IPTV application.
                            failed = true
                            mutableStatus.value = VpnStatus(VpnPhase.DISCONNECTING, id)
                            stopSelf()
                        }
                    }
                    while (!destroyed && status.value.phase == VpnPhase.UP) {
                        delay(2_000)
                        mutex.withLock {
                            if (!destroyed) {
                                try {
                                    val stats = backend?.getStatistics(tunnel)
                                    val latest = stats?.peers()?.maxOfOrNull {
                                        stats.peer(it)?.latestHandshakeEpochMillis() ?: 0L
                                    } ?: 0L
                                    mutableStatus.update { it.copy(handshakeAt = latest) }
                                } catch (_: Exception) { /* Retain last observed handshake. */ }
                            }
                        }
                    }
                }
            }
        }
        // Reconnection is explicit after process death/reboot; never claim an inactive VPN is up.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
        scope.launch {
            mutex.withLock {
                try {
                    backend?.setState(tunnel, Tunnel.State.DOWN, null)
                } catch (_: Exception) {
                    failed = true
                }
                try {
                    stopService(Intent(this@WireGuardForegroundService, WireGuardTunnelService::class.java))
                    withTimeout(5_000) { WireGuardTunnelService.ready.first { !it } }
                } catch (_: Exception) {
                    failed = true
                } finally {
                    mutableStatus.value = VpnStatus(if (failed) VpnPhase.ERROR else VpnPhase.OFF)
                    scope.cancel()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val PROFILE_ID = "profile_id"
        internal const val DISCONNECT = "com.streamvault.app.vpn.DISCONNECT"
        private val mutableStatus = MutableStateFlow(VpnStatus())
        internal val status = mutableStatus.asStateFlow()

        internal fun connect(context: Context, id: String) {
            if (status.value.occupied) return
            mutableStatus.value = VpnStatus(VpnPhase.CONNECTING, id)
            try {
                ContextCompat.startForegroundService(context,
                    Intent(context, WireGuardForegroundService::class.java).putExtra(PROFILE_ID, id))
            } catch (_: Exception) {
                mutableStatus.value = VpnStatus(VpnPhase.ERROR)
            }
        }

        internal fun disconnect(context: Context) {
            if (!status.value.occupied) return
            mutableStatus.update { it.copy(phase = VpnPhase.DISCONNECTING) }
            context.startService(Intent(context, WireGuardForegroundService::class.java).setAction(DISCONNECT))
        }
    }
}
