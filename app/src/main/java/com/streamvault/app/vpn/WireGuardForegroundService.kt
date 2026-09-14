package com.streamvault.app.vpn

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.streamvault.app.R
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
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
    val handshakeAt: Long = 0L,
    val connected: Boolean = false,
    val progress: Float = 0f,
    val message: Int? = null
) {
    val occupied: Boolean get() = phase in setOf(VpnPhase.CONNECTING, VpnPhase.UP, VpnPhase.DISCONNECTING)
}

class WireGuardForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var backend: GoBackend? = null
    @Volatile private var destroyed = false
    @Volatile private var failed = false
    private var started = false
    private var failureMessage = R.string.wg_error_connect
    private val tunnel = object : Tunnel {
        override fun getName() = "streamvault"
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN && status.value.phase == VpnPhase.UP) {
                WireGuardNetworkGuard.connected(null)
                failed = true
                mutableStatus.update { it.copy(phase = VpnPhase.DISCONNECTING, connected = false) }
                stopSelf()
            }
        }
    }

    override fun onCreate() { super.onCreate(); showWireGuardNotification() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DISCONNECT) {
            WireGuardNetworkGuard.connected(null)
            mutableStatus.update { it.copy(phase = VpnPhase.DISCONNECTING, connected = false) }
            stopSelf()
        } else if (!started) {
            started = true
            val id = intent?.getStringExtra(PROFILE_ID)
            if (id == null) stopSelf() else {
                mutableStatus.value = VpnStatus(VpnPhase.CONNECTING, id, progress = 0.15f, message = R.string.wg_step_profile)
                scope.launch {
                    try {
                        val config = WireGuardProfileStore(applicationContext).config(id)
                        mutex.withLock {
                            if (destroyed) return@launch
                            val engine = GoBackend(applicationContext).also { backend = it }
                            mutableStatus.update { it.copy(progress = 0.35f, message = R.string.wg_step_tunnel) }
                            startService(Intent(this@WireGuardForegroundService, WireGuardTunnelService::class.java))
                            withTimeout(5_000) { WireGuardTunnelService.ready.first { it } }
                            if (destroyed) return@launch
                            engine.setState(tunnel, Tunnel.State.UP, config)
                        }
                        val network = withTimeout(8_000) {
                            var found = findOwnNetwork(config)
                            while (found == null) { delay(100); found = findOwnNetwork(config) }
                            found
                        }
                        mutableStatus.update { it.copy(progress = 0.7f, message = R.string.wg_step_handshake) }
                        val health = VpnConnectionHealth()
                        withTimeout(30_000) {
                            while (!destroyed) {
                                val reply = WireGuardHealthProbe.check(network, config.`interface`.dnsServers.first())
                                val handshake = latestHandshake()
                                if (health.observe(SystemClock.elapsedRealtime(), handshake, reply) && handshake > 0) {
                                    if (destroyed || status.value.phase == VpnPhase.DISCONNECTING) return@withTimeout
                                    WireGuardNetworkGuard.connected(network)
                                    mutableStatus.value = VpnStatus(VpnPhase.UP, id, handshake, true, 1f)
                                    break
                                }
                                delay(1_000)
                            }
                        }
                        while (!destroyed) {
                            delay(8_000)
                            val sameNetwork = findOwnNetwork(config) == network
                            val reply = sameNetwork && WireGuardHealthProbe.check(network, config.`interface`.dnsServers.first())
                            val handshake = latestHandshake()
                            val alive = sameNetwork && health.observe(SystemClock.elapsedRealtime(), handshake, reply)
                            if (destroyed || status.value.phase == VpnPhase.DISCONNECTING) break
                            WireGuardNetworkGuard.connected(if (alive) network else null)
                            mutableStatus.update {
                                it.copy(connected = alive, handshakeAt = handshake,
                                    message = if (alive) null else R.string.wg_connection_lost)
                            }
                        }
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                        fail(R.string.wg_connection_timeout)
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        // Service teardown already closes the gate.
                    } catch (_: Exception) {
                        fail(R.string.wg_error_connect)
                    } catch (_: LinkageError) {
                        fail(R.string.wg_error_connect)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun fail(message: Int) {
        if (destroyed) return
        failed = true
        failureMessage = message
        WireGuardNetworkGuard.connected(null)
        mutableStatus.update { it.copy(phase = VpnPhase.DISCONNECTING, connected = false, message = message) }
        stopSelf()
    }

    private suspend fun latestHandshake(): Long = mutex.withLock {
        if (destroyed) return@withLock 0L
        val stats = backend?.getStatistics(tunnel)
        stats?.peers()?.maxOfOrNull { stats.peer(it)?.latestHandshakeEpochMillis() ?: 0L } ?: 0L
    }

    private fun findOwnNetwork(config: Config): Network? {
        val manager = getSystemService(ConnectivityManager::class.java)
        val addresses = config.`interface`.addresses.map { it.address }.toSet()
        return manager.allNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                manager.getLinkProperties(network)?.linkAddresses?.any { it.address in addresses } == true
        }
    }

    override fun onDestroy() {
        destroyed = true
        WireGuardNetworkGuard.connected(null)
        super.onDestroy()
        scope.launch {
            mutex.withLock {
                try { backend?.setState(tunnel, Tunnel.State.DOWN, null) }
                catch (_: Exception) { failed = true }
                try {
                    stopService(Intent(this@WireGuardForegroundService, WireGuardTunnelService::class.java))
                    withTimeout(5_000) { WireGuardTunnelService.ready.first { !it } }
                } catch (_: Exception) { failed = true }
                finally {
                    mutableStatus.value = VpnStatus(
                        if (failed) VpnPhase.ERROR else VpnPhase.OFF,
                        message = if (failed) failureMessage else null)
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

        internal fun reportFailure(message: Int) {
            if (!status.value.occupied) mutableStatus.value = VpnStatus(VpnPhase.ERROR, message = message)
        }

        internal fun autoConnect(context: Context) {
            val options = WireGuardPreferences.get(context).state.value
            if (!options.autoConnect || status.value.occupied || WireGuardUi.settingsVisible) return
            val id = options.profileId ?: run { reportFailure(R.string.wg_select_profile); return }
            try {
                if (VpnService.prepare(context) != null) {
                    reportFailure(R.string.wg_auto_permission)
                } else connect(context, id)
            } catch (_: Exception) { reportFailure(R.string.wg_error_permission) }
        }

        internal fun connect(context: Context, id: String) {
            if (status.value.occupied) return
            WireGuardPreferences.get(context).update { it.copy(profileId = id) }
            WireGuardNetworkGuard.connected(null)
            mutableStatus.value = VpnStatus(VpnPhase.CONNECTING, id, progress = 0.05f, message = R.string.wg_step_profile)
            try {
                ContextCompat.startForegroundService(context,
                    Intent(context, WireGuardForegroundService::class.java).putExtra(PROFILE_ID, id))
            } catch (_: Exception) { reportFailureAfterStart() }
        }

        private fun reportFailureAfterStart() {
            WireGuardNetworkGuard.connected(null)
            mutableStatus.value = VpnStatus(VpnPhase.ERROR, message = R.string.wg_error_connect)
        }

        internal fun disconnect(context: Context) {
            if (!status.value.occupied) return
            WireGuardNetworkGuard.connected(null)
            mutableStatus.update { it.copy(phase = VpnPhase.DISCONNECTING, connected = false) }
            try {
                context.startService(Intent(context, WireGuardForegroundService::class.java).setAction(DISCONNECT))
            } catch (_: Exception) { context.stopService(Intent(context, WireGuardForegroundService::class.java)) }
        }
    }
}
