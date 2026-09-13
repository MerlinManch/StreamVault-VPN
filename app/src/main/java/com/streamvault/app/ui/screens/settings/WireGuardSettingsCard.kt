package com.streamvault.app.ui.screens.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.net.VpnService
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogActionButton
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.Primary
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
    var name by rememberSaveable { mutableStateOf("") }
    // Config contains a private key: never persist it in savedInstanceState.
    var config by remember { mutableStateOf("") }
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
        if (uri != null) model.importFile(name, uri)
    }
    LaunchedEffect(state.importFinished) {
        if (state.importFinished > 0) {
            showImport = false
            config = ""
            name = ""
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
    if (showProfiles) {
        PremiumDialog(
            title = stringResource(R.string.wg_title),
            subtitle = stringResource(R.string.wg_scope),
            widthFraction = 0.7f,
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
                        enabled = status.phase == VpnPhase.UP,
                        onClick = { WireGuardForegroundService.disconnect(context) })
                }
                PremiumDialogActionButton(
                    label = stringResource(R.string.wg_add), enabled = !state.busy && state.profiles.size < 32,
                    onClick = { config = ""; name = ""; showImport = true })
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
        PremiumDialog(
            title = stringResource(R.string.wg_add),
            subtitle = stringResource(R.string.wg_import_hint),
            widthFraction = 0.75f,
            onDismissRequest = { if (!state.busy) { showImport = false; config = "" } },
            content = {
                val view = LocalView.current
                DisposableEffect(view) {
                    val window = (view.parent as? DialogWindowProvider)?.window
                    window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                }
                OutlinedTextField(name, { if (it.length <= 64) name = it },
                    label = { Text(stringResource(R.string.wg_name)) }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = OnSurface,
                        unfocusedTextColor = OnSurface, cursorColor = Primary),
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                PremiumDialogActionButton(
                    label = stringResource(R.string.wg_import_file), enabled = name.isNotBlank() && !state.busy,
                    onClick = {
                        try { picker.launch(arrayOf("*/*")) }
                        catch (_: ActivityNotFoundException) { model.reportError(R.string.wg_error_picker) }
                    })
                OutlinedTextField(config, { if (it.length <= 65_536) config = it },
                    label = { Text(stringResource(R.string.wg_config)) }, minLines = 3, maxLines = 6,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = OnSurface,
                        unfocusedTextColor = OnSurface, cursorColor = Primary),
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                state.error?.let { Text(stringResource(it), color = OnSurface) }
                PremiumDialogActionButton(
                    label = stringResource(R.string.wg_save), enabled = name.isNotBlank() && config.isNotBlank() && !state.busy,
                    onClick = { model.importText(name, config) })
            },
            footer = {
                PremiumDialogFooterButton(stringResource(R.string.wg_cancel), enabled = !state.busy,
                    onClick = { showImport = false; config = "" })
            }
        )
    }
    deleteId?.let { id ->
        PremiumDialog(
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
