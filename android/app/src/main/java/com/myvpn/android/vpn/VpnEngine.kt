package com.myvpn.android.vpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface VpnConnectionState {
    data object Disconnected : VpnConnectionState
    data object Connecting : VpnConnectionState
    data object Connected : VpnConnectionState
    data object Disconnecting : VpnConnectionState
    data object PermissionDenied : VpnConnectionState
    data class Failed(val message: String) : VpnConnectionState
}

object VpnConnectionStore {
    private val mutableState = MutableStateFlow<VpnConnectionState>(VpnConnectionState.Disconnected)
    val state: StateFlow<VpnConnectionState> = mutableState
    fun update(state: VpnConnectionState) { mutableState.value = state }
}

interface VpnEngine {
    val state: StateFlow<VpnConnectionState>
    suspend fun start(configuration: String)
    suspend fun stop()
}

class LibXrayVpnEngine(private val context: Context) : VpnEngine {
    override val state: StateFlow<VpnConnectionState> = VpnConnectionStore.state
    override suspend fun start(configuration: String) {
        if (state.value is VpnConnectionState.Connecting || state.value is VpnConnectionState.Connected) return
        ContextCompat.startForegroundService(context, MyVpnService.connectIntent(context, configuration))
    }
    override suspend fun stop() {
        context.startService(MyVpnService.disconnectIntent(context))
    }
}
