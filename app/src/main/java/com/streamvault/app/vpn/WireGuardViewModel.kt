package com.streamvault.app.vpn

import android.app.Application
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class VpnProfilesState(
    val profiles: List<VpnProfile> = emptyList(),
    val busy: Boolean = true,
    val error: Int? = null,
    val importFinished: Int = 0
)

internal data class WireGuardPairingState(val qr: Bitmap? = null, val error: Int? = null)

internal class WireGuardViewModel(application: Application) : AndroidViewModel(application) {
    private val store = WireGuardProfileStore(application)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(VpnProfilesState())
    val state = mutableState.asStateFlow()
    val status = WireGuardForegroundService.status
    private val preferences = WireGuardPreferences.get(application)
    val options = preferences.state

    fun setAutoConnect(value: Boolean) = changeOptions { it.copy(autoConnect = value,
        profileId = it.profileId ?: state.value.profiles.firstOrNull()?.id) }
    fun setKillSwitch(value: Boolean) = changeOptions { it.copy(killSwitch = value) }
    fun setShowStatus(value: Boolean) = changeOptions { it.copy(showStatus = value) }
    fun selectProfile(id: String) = changeOptions { it.copy(profileId = id) }
    private fun changeOptions(transform: (WireGuardOptions) -> WireGuardOptions) {
        try { preferences.update(transform) } catch (_: Exception) { reportError(R.string.wg_error_storage) }
    }

    private val pairingLock = Any()
    private var pairingGeneration = 0
    private var pairingServer: WireGuardPairingServer? = null
    private val mutablePairing = MutableStateFlow(WireGuardPairingState())
    val pairing = mutablePairing.asStateFlow()

    fun startPairing() {
        stopPairing()
        val generation = synchronized(pairingLock) { pairingGeneration }
        viewModelScope.launch(Dispatchers.IO) {
            synchronized(pairingLock) {
                if (generation != pairingGeneration) return@launch
                try {
                    val app = getApplication<Application>()
                    val manager = app.getSystemService(ConnectivityManager::class.java)
                    // Select a physical Wi-Fi/Ethernet address, never a VPN/tunnel interface.
                    val address = manager.allNetworks.asSequence().filter { network ->
                        val caps = manager.getNetworkCapabilities(network)
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false &&
                            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                    }.flatMap { manager.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence() }
                        .map { it.address }.filterIsInstance<Inet4Address>()
                        .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                        ?: error("No LAN address")
                    val server = WireGuardPairingServer(address,
                        save = { name, config -> store.add(name, config) },
                        saved = { viewModelScope.launch {
                            val current = generation == synchronized(pairingLock) { pairingGeneration }
                            if (current) stopPairing()
                            operation(importing = current) { }
                        } },
                        expired = { viewModelScope.launch {
                            if (generation == synchronized(pairingLock) { pairingGeneration }) {
                                stopPairing()
                                mutablePairing.value = WireGuardPairingState(error = R.string.wg_pairing_expired)
                            }
                        } })
                    pairingServer = server
                    val size = 384
                    val matrix = QRCodeWriter().encode(server.url, BarcodeFormat.QR_CODE, size, size,
                        mapOf(EncodeHintType.MARGIN to 2))
                    val pixels = IntArray(size * size) { i ->
                        if (matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                    }
                    mutablePairing.value = WireGuardPairingState(
                        qr = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888))
                } catch (_: Exception) {
                    pairingServer?.close()
                    pairingServer = null
                    mutablePairing.value = WireGuardPairingState(error = R.string.wg_pairing_error)
                }
            }
        }
    }

    fun stopPairing() {
        synchronized(pairingLock) {
            pairingGeneration++
            pairingServer?.close()
            pairingServer = null
            mutablePairing.value = WireGuardPairingState()
        }
    }

    override fun onCleared() { stopPairing(); super.onCleared() }

    init { operation { } }

    fun importText(name: String, config: String) = operation(importing = true) { store.add(name, config) }

    fun importFile(name: String, uri: Uri) = operation(importing = true) {
        val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (out.size() + count > WireGuardConfigPolicy.MAX_BYTES) {
                    throw VpnProfileException(VpnProfileException.Reason.TOO_LARGE)
                }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } ?: throw VpnProfileException(VpnProfileException.Reason.STORAGE)
        store.add(name, String(bytes, Charsets.UTF_8))
    }

    fun delete(id: String) = operation {
        check(!status.value.occupied || status.value.profileId != id)
        store.delete(id)
        if (options.value.profileId == id) preferences.update { it.copy(profileId = null, autoConnect = false) }
    }

    fun reportError(message: Int) { mutableState.value = mutableState.value.copy(error = message) }

    private fun operation(importing: Boolean = false, block: () -> Unit) {
        viewModelScope.launch {
            mutex.withLock {
                mutableState.value = mutableState.value.copy(busy = true, error = null)
                try {
                    val profiles = withContext(Dispatchers.IO) { block(); store.list() }
                    mutableState.value = mutableState.value.copy(profiles = profiles, busy = false,
                        importFinished = mutableState.value.importFinished + if (importing) 1 else 0)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val message = when ((error as? VpnProfileException)?.reason) {
                        VpnProfileException.Reason.INVALID -> R.string.wg_error_config
                        VpnProfileException.Reason.FULL_TUNNEL -> R.string.wg_error_routes
                        VpnProfileException.Reason.DNS -> R.string.wg_error_dns
                        VpnProfileException.Reason.TOO_LARGE -> R.string.wg_error_size
                        else -> R.string.wg_error_storage
                    }
                    mutableState.value = mutableState.value.copy(busy = false, error = message)
                }
            }
        }
    }
}
