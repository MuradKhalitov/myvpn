package com.myvpn.android.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myvpn.android.MyVpnApplication
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var viewModel: MainViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MyVpnApplication
        val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel?.onPermissionResult(it.resultCode == Activity.RESULT_OK) }
        val dialer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel?.onReturnedFromDialer() }
        setContent {
            val vm = remember { MainViewModel(app.phoneAuth, app.access, app.engine) }
            DisposableEffect(Unit) { viewModel = vm; onDispose { viewModel = null } }
            val state by vm.state.collectAsState()
            LaunchedEffect(state) { if (state is MainUiState.AwaitingPermission) VpnService.prepare(this@MainActivity)?.let(permission::launch) ?: vm.onPermissionResult(true) }
            MainScreen(state, vm) { callPhone -> dialer.launch(dialIntent(callPhone)) }
        }
    }
}

internal data class DialRequest(val action: String, val uri: String)
internal fun dialRequest(callPhone: String) = DialRequest(Intent.ACTION_DIAL, "tel:$callPhone")
internal fun dialIntent(callPhone: String): Intent = dialRequest(callPhone).let { Intent(it.action, Uri.parse(it.uri)) }

@Composable
private fun MainScreen(state: MainUiState, vm: MainViewModel, onDial: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("MyVPN", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))
        when (state) {
            MainUiState.Initializing, MainUiState.Authenticated, MainUiState.Connecting, MainUiState.Disconnecting, MainUiState.AwaitingPermission -> Loading("Проверяем сессию")
            is MainUiState.PhoneEntry -> PhoneEntry(state, vm)
            is MainUiState.PhoneVerification -> PhoneVerification(state, onDial)
            is MainUiState.VpnProvisioning -> Loading("Настраиваем VPN")
            is MainUiState.Ready -> ReadyScreen(state, vm)
            is MainUiState.Expired -> { Text(state.message); Spacer(Modifier.height(12.dp)); Button(onClick = vm::retry) { Text("Выбрать тариф") } }
            is MainUiState.Error -> { Text(state.message); if (state.retryable) { Spacer(Modifier.height(12.dp)); Button(onClick = vm::retry) { Text("Повторить") } } }
            MainUiState.Connected -> { Text("VPN подключён"); Button(onClick = vm::disconnect) { Text("Отключить") } }
        }
    }
}

@Composable private fun Loading(message: String) { Text(message); Spacer(Modifier.height(12.dp)); CircularProgressIndicator() }

@Composable
private fun PhoneEntry(state: MainUiState.PhoneEntry, vm: MainViewModel) {
    var phone by remember { mutableStateOf("") }
    Text("Войдите по номеру телефона")
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Телефон") }, placeholder = { Text("+7 999 123-45-67") }, singleLine = true)
    state.validationMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Spacer(Modifier.height(12.dp))
    Button(onClick = { vm.startPhoneVerification(phone) }) { Text("Продолжить") }
}

@Composable
private fun PhoneVerification(state: MainUiState.PhoneVerification, onDial: (String) -> Unit) {
    Text("Позвоните на номер для подтверждения")
    Spacer(Modifier.height(8.dp))
    Text(state.verification.callPhonePretty ?: state.verification.callPhone, style = MaterialTheme.typography.headlineSmall)
    Text("Осталось: ${remainingTime(state.verification.expiresAt)}")
    state.message?.let { Text(it) }
    Spacer(Modifier.height(12.dp))
    Button(onClick = { onDial(state.verification.callPhone) }) { Text("Позвонить") }
}

@Composable
private fun ReadyScreen(state: MainUiState.Ready, vm: MainViewModel) {
    when (state.access.entitlement) {
        "TRIAL" -> {
            Text("Пробный период", style = MaterialTheme.typography.headlineSmall)
            Text("Безлимитный трафик")
            state.session.accessExpiresAt?.let { Text("Осталось: ${remainingTime(it)}") }
        }
        "PREMIUM" -> {
            Text("Premium", style = MaterialTheme.typography.headlineSmall)
            Text("Безлимитный трафик")
            state.access.premiumExpiresAt?.let { Text("Действует до: $it") }
        }
        else -> Text(state.access.entitlement)
    }
    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Spacer(Modifier.height(12.dp))
    Button(onClick = vm::connect) { Text("Подключить") }
}

@Composable
private fun remainingTime(expiresAt: String): String {
    val value by produceState(initialValue = "—", expiresAt) {
        while (true) {
            value = runCatching {
                val remaining = Duration.between(Instant.now(), Instant.parse(expiresAt))
                if (remaining.isNegative || remaining.isZero) "истёк" else "${remaining.toDays()} д ${remaining.toHoursPart()} ч ${remaining.toMinutesPart()} мин"
            }.getOrDefault("—")
            delay(1_000)
        }
    }
    return value
}
