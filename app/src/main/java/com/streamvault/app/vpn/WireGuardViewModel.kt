package com.streamvault.app.vpn

import android.app.Application
import android.net.Uri
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

internal class WireGuardViewModel(application: Application) : AndroidViewModel(application) {
    private val store = WireGuardProfileStore(application)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(VpnProfilesState())
    val state = mutableState.asStateFlow()
    val status = WireGuardForegroundService.status

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
