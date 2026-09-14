package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.vpn.VpnPhase
import com.streamvault.app.vpn.WireGuardForegroundService
import com.streamvault.app.vpn.WireGuardUi
import kotlinx.coroutines.delay

@Composable
internal fun WireGuardAppOverlay() {
    val open by WireGuardUi.open.collectAsStateWithLifecycle()
    val status by WireGuardForegroundService.status.collectAsStateWithLifecycle()
    var showSuccess by remember { mutableStateOf(false) }
    LaunchedEffect(status.connected) {
        showSuccess = status.connected
        if (showSuccess) { delay(3_000); showSuccess = false }
    }
    val warning = status.phase == VpnPhase.ERROR || (status.phase == VpnPhase.UP && !status.connected)
    var showWarning by remember { mutableStateOf(false) }
    LaunchedEffect(warning, status.message) {
        showWarning = warning
        if (warning) { delay(12_000); showWarning = false }
    }
    if (status.phase == VpnPhase.CONNECTING || showSuccess || showWarning) {
        // A non-focusable overlay: it never steals D-pad input from the current screen.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
            Column(
                modifier = Modifier.padding(22.dp).width(320.dp)
                    .background(if (warning) Color(0xFF612A20) else Color(0xFF172635), RoundedCornerShape(14.dp))
                    .padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(if (warning) R.string.wg_warning else if (status.connected)
                    R.string.wg_nav_connected else R.string.wg_connecting), color = Color.White)
                if (!warning) LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
                status.message?.let { Text(stringResource(it), color = Color.White) }
            }
        }
    }
    if (open) {
        WireGuardSettingsCard(openImmediately = true, showEntry = false, onClosed = WireGuardUi::close)
    }
}
