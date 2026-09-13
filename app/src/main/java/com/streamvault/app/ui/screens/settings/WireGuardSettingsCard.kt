package com.streamvault.app.ui.screens.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogActionButton
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.vpn.VpnPhase
import com.streamvault.app.vpn.WireGuardForegroundService
import com.streamvault.app.vpn.WireGuardViewModel

@Composable
internal fun WireGuardSettingsCard(model: WireGuardViewModel = viewModel()) {
    val context = LocalContext.current
    val state by model.state.collectAsStateWithLifecycle()
    val status by model.status.collectAsStateWithLifecycle()
    var showProfiles by rememberSaveable { mutableStateOf(false) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val pairing by model.pairing.collectAsStateWithLifecycle()
    val addFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pendingId
        pendingId = null
        if (result.resultCode == Activity.RESULT_OK && id != null) {
            WireGuardForegroundService.connect(context, id)
        } else {
            model.reportError(R.string.wg_error_permission)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importFile("WireGuard", uri)
    }
    LaunchedEffect(state.importFinished) {
        if (state.importFinished > 0) {
            showImport = false
            model.stopPairing()
        }
    }
    val statusText = stringResource(when (status.phase) {
        VpnPhase.OFF -> R.string.wg_off
        VpnPhase.CONNECTING -> R.string.wg_connecting
        VpnPhase.UP -> R.string.wg_up
        VpnPhase.DISCONNECTING -> R.string.wg_disconnecting
        VpnPhase.ERROR -> R.string.wg_error_connect
    })
    ClickableSettingsRow(stringResource(R.string.wg_title), statusText, { showProfiles = true })
    if (showProfiles && !showImport && deleteId == null) {
        PremiumDialog(
            title = stringResource(R.string.wg_title),
            subtitle = stringResource(R.string.wg_scope),
            widthFraction = 0.7f,
            scrollOnDirectionalKey = false,
            initialBodyFocusRequester = addFocus,
            onDismissRequest = { showProfiles = false },
            content = {
                Text(statusText, color = OnSurface)
                if (status.phase == VpnPhase.UP) {
                    Text(stringResource(if (status.handshakeAt > 0) R.string.wg_handshake else R.string.wg_waiting),
                        color = OnSurface)
                }
                state.error?.let { Text(stringResource(it), color = OnSurface) }
                if (status.occupied) {
                    PremiumDialogActionButton(
                        label = stringResource(R.string.wg_disconnect),
                        modifier = Modifier.focusRequester(addFocus),
                        enabled = status.phase == VpnPhase.UP,
                        onClick = { WireGuardForegroundService.disconnect(context) })
                }
                PremiumDialogActionButton(
                    label = stringResource(R.string.wg_add), enabled = !status.occupied && !state.busy && state.profiles.size < 32,
                    modifier = if (!status.occupied) Modifier.focusRequester(addFocus) else Modifier,
                    onClick = { showImport = true })
                if (state.profiles.isEmpty() && !state.busy) Text(stringResource(R.string.wg_empty), color = OnSurface)
                state.profiles.forEach { profile ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ClickableSettingsRow(
                            label = profile.name.take(32),
                            value = stringResource(if (status.profileId == profile.id) R.string.wg_selected else R.string.wg_connect),
                            enabled = !status.occupied && pendingId == null && !state.busy,
                            onClick = {
                                try {
                                    val consent = VpnService.prepare(context)
                                    if (consent == null) WireGuardForegroundService.connect(context, profile.id)
                                    else { pendingId = profile.id; permission.launch(consent) }
                                } catch (_: Exception) {
                                    pendingId = null
                                    model.reportError(R.string.wg_error_permission)
                                }
                            })
                        PremiumDialogActionButton(
                            label = stringResource(R.string.wg_delete),
                            enabled = !state.busy && !status.occupied && pendingId == null,
                            onClick = { deleteId = profile.id })
                    }
                }
                Text(stringResource(R.string.wg_lifecycle), color = OnSurface)
            },
            footer = {
                PremiumDialogFooterButton(stringResource(R.string.wg_close), onClick = { showProfiles = false })
            }
        )
    }
    if (showImport) {
        DisposableEffect(model) {
            model.startPairing()
            onDispose { model.stopPairing() }
        }
        PremiumDialog(
            title = stringResource(R.string.wg_add),
            subtitle = stringResource(R.string.wg_import_hint),
            widthFraction = 0.8f,
            bodyHeightFraction = 0.62f,
            heightFraction = 0.94f,
            scrollOnDirectionalKey = false,
            initialBodyFocusRequester = retryFocus,
            onDismissRequest = { showImport = false },
            content = {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    pairing.qr?.let { bitmap ->
                        Image(bitmap.asImageBitmap(), stringResource(R.string.wg_phone),
                            modifier = Modifier.size(156.dp))
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.wg_phone_hint), color = OnSurface)
                        pairing.error?.let { Text(stringResource(it), color = OnSurface) }
                    }
                }
                state.error?.let { Text(stringResource(it), color = OnSurface) }
                PremiumDialogActionButton(
                    label = stringResource(R.string.wg_pairing_retry),
                    modifier = Modifier.focusRequester(retryFocus),
                    onClick = { model.startPairing() })
                PremiumDialogActionButton(
                    label = stringResource(R.string.wg_import_file), enabled = !state.busy,
                    onClick = {
                        try { picker.launch(arrayOf("*/*")) }
                        catch (_: ActivityNotFoundException) { model.reportError(R.string.wg_error_picker) }
                    })
            },
            footer = {
                PremiumDialogFooterButton(stringResource(R.string.wg_cancel),
                    onClick = { showImport = false })
            }
        )
    }
    deleteId?.let { id ->
        PremiumDialog(
            scrollOnDirectionalKey = false,
            title = stringResource(R.string.wg_delete),
            subtitle = stringResource(R.string.wg_delete_confirm),
            onDismissRequest = { deleteId = null },
            content = {
                PremiumDialogActionButton(stringResource(R.string.wg_delete), onClick = {
                    model.delete(id); deleteId = null
                })
            },
            footer = { PremiumDialogFooterButton(stringResource(R.string.wg_cancel), onClick = { deleteId = null }) }
        )
    }
}
