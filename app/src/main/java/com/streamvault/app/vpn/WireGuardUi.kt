package com.streamvault.app.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object WireGuardUi {
    private val mutableOpen = MutableStateFlow(false)
    val open = mutableOpen.asStateFlow()
    fun show() { mutableOpen.value = true }
    fun close() { mutableOpen.value = false }
}
