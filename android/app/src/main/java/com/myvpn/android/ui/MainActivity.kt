package com.myvpn.android.ui

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myvpn.android.MyVpnApplication

class MainActivity : ComponentActivity() {
    private var viewModel: MainViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MyVpnApplication
        val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel?.onPermissionResult(it.resultCode == Activity.RESULT_OK)
        }
        setContent {
            val vm = remember { MainViewModel(app.access, app.engine) }
            DisposableEffect(Unit) { viewModel = vm; onDispose { viewModel = null } }
            val state by vm.state.collectAsState()
            LaunchedEffect(state) {
                if (state is MainUiState.AwaitingPermission) {
                    VpnService.prepare(this@MainActivity)?.let(permission::launch) ?: vm.onPermissionResult(true)
                }
            }
            MainScreen(state, vm)
        }
    }
}

@androidx.compose.runtime.Composable
private fun MainScreen(state: MainUiState, vm: MainViewModel) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("MyVPN", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))
        when (state) {
            MainUiState.Initializing, MainUiState.Registering, MainUiState.Connecting,
            MainUiState.Disconnecting, MainUiState.AwaitingPermission -> CircularProgressIndicator()
            is MainUiState.Provisioning -> { Text("Preparing VPN…"); CircularProgressIndicator() }
            MainUiState.RetryRequired -> { Text("VPN is being prepared"); Button(onClick = vm::reload) { Text("Retry") } }
            is MainUiState.Ready -> { Text(state.message ?: state.access.entitlement); Button(onClick = vm::connect) { Text("CONNECT") } }
            MainUiState.Connected -> { Text("Connected"); Button(onClick = vm::disconnect) { Text("DISCONNECT") } }
            is MainUiState.Error -> { Text(state.message); Button(onClick = vm::reload) { Text("Retry") } }
        }
    }
}
