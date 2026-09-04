package com.myvpn.android.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.myvpn.android.MyVpnApplication
import com.myvpn.android.data.Session
import com.myvpn.android.data.VpnAccessResponse
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
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(state, vm) { callPhone -> dialer.launch(dialIntent(callPhone)) }
                }
            }
        }
    }
}

internal data class DialRequest(val action: String, val uri: String)
internal fun dialRequest(callPhone: String) = DialRequest(Intent.ACTION_DIAL, "tel:$callPhone")
internal fun dialIntent(callPhone: String): Intent = dialRequest(callPhone).let { Intent(it.action, Uri.parse(it.uri)) }

@Composable
private fun MainScreen(state: MainUiState, vm: MainViewModel, onDial: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("MyVPN", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(32.dp))
        when (state) {
            MainUiState.Initializing, MainUiState.Authenticated, MainUiState.Connecting, MainUiState.Disconnecting, MainUiState.AwaitingPermission -> Loading("Проверяем сессию")
            is MainUiState.PhoneEntry -> PhoneEntry(state, vm)
            is MainUiState.PhoneVerification -> PhoneVerification(state, vm, onDial)
            is MainUiState.VpnProvisioning -> Loading("Настраиваем VPN")
            is MainUiState.Ready -> VpnScreen(state.access, state.session, connected = false, state.message, vm)
            is MainUiState.Connected -> VpnScreen(state.access, state.session, connected = true, null, vm)
            is MainUiState.Expired -> ExpiredScreen(state.message, vm)
            is MainUiState.Error -> ErrorScreen(state, vm)
        }
    }
}

@Composable private fun Loading(message: String) { Spacer(Modifier.height(64.dp)); CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text(message, style = MaterialTheme.typography.titleMedium) }

@Composable
private fun PhoneEntry(state: MainUiState.PhoneEntry, vm: MainViewModel) {
    var phone by remember { mutableStateOf(TextFieldValue("")) }
    val phoneInput = PhoneNumberInput(phone.text)
    Text("Войдите по номеру телефона", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text("Подтвердим номер бесплатным звонком. Это займёт меньше минуты.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(28.dp))
    OutlinedTextField(
        value = phone,
        onValueChange = { phone = PhoneNumberInputFormatter.normalize(it) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Номер телефона") },
        prefix = { Text(phoneInput.prefix) },
        placeholder = { Text(phoneInput.placeholder) },
        visualTransformation = PhoneNumberVisualTransformation,
        singleLine = true,
        isError = state.validationMessage != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done)
    )
    state.validationMessage?.let { Text(it, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Spacer(Modifier.height(20.dp))
    Button(onClick = { vm.startPhoneVerification(phoneInput.canonical) }, modifier = Modifier.fillMaxWidth(), enabled = phoneInput.isComplete) { Text("Продолжить") }
}

@Composable
private fun PhoneVerification(state: MainUiState.PhoneVerification, vm: MainViewModel, onDial: (String) -> Unit) {
    Text("Подтвердите номер", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    Text("Позвоните на этот номер", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    Text(state.verification.callPhonePretty ?: state.verification.callPhone, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(12.dp))
    Text("Звонок бесплатный. После соединения вернитесь в приложение.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    Text(verificationCountdown(state.verification.expiresAt), style = MaterialTheme.typography.titleMedium)
    if (!state.waitingForCall) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.size(10.dp)); Text("Проверяем звонок...") }
    }
    Spacer(Modifier.height(24.dp))
    Button(onClick = { onDial(state.verification.callPhone) }, modifier = Modifier.fillMaxWidth()) { Text("Позвонить") }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = vm::changePhoneNumber, modifier = Modifier.fillMaxWidth()) { Text("Изменить номер") }
}

@Composable
private fun VpnScreen(access: VpnAccessResponse, session: Session, connected: Boolean, message: String?, vm: MainViewModel) {
    ConnectionHeader(connected)
    Spacer(Modifier.height(28.dp))
    EntitlementCard(access, session)
    message?.let { Text(it, modifier = Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.error) }
    Spacer(Modifier.height(32.dp))
    Button(onClick = { if (connected) vm.disconnect() else vm.connect() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (connected) "Отключить VPN" else "Подключить VPN") }
}

@Composable
private fun ConnectionHeader(connected: Boolean) {
    val indicator = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).background(indicator, CircleShape))
        Spacer(Modifier.size(10.dp))
        Text(if (connected) "VPN подключён" else "VPN отключён", style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun EntitlementCard(access: VpnAccessResponse, session: Session) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(24.dp)) {
            when (access.entitlement) {
                "TRIAL" -> {
                    Text("Пробный период", style = MaterialTheme.typography.titleLarge)
                    Text("Безлимитный трафик", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    session.accessExpiresAt?.let { Text(trialRemaining(it), modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleMedium) }
                }
                "PREMIUM" -> {
                    Text("Premium", style = MaterialTheme.typography.titleLarge)
                    Text("Безлимитный трафик", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PresentationFormatter.premiumUntil(access.premiumExpiresAt)?.let { Text(it, modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleMedium) }
                }
                else -> {
                    Text(access.entitlement, style = MaterialTheme.typography.titleLarge)
                    Text("Доступ к VPN", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ExpiredScreen(message: String, vm: MainViewModel) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(24.dp)) {
            Text("Доступ закончился", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Выберите тариф, чтобы снова подключить VPN", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Button(onClick = vm::retry, modifier = Modifier.fillMaxWidth()) { Text("Выбрать тариф") }
        }
    }
}

@Composable
private fun ErrorScreen(state: MainUiState.Error, vm: MainViewModel) {
    Text(state.message, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))
    if (state.retryable) Button(onClick = vm::retry) { Text(if (state.type == AppError.VERIFICATION_EXPIRED || state.type == AppError.VERIFICATION_FAILED) "Изменить номер" else "Повторить") }
}

@Composable
private fun verificationCountdown(expiresAt: String): String {
    val value by produceState(initialValue = PresentationFormatter.verificationCountdown(expiresAt), expiresAt) {
        while (true) { value = PresentationFormatter.verificationCountdown(expiresAt); delay(1_000) }
    }
    return value
}

private fun trialRemaining(expiresAt: String) = PresentationFormatter.trialRemaining(expiresAt) ?: "Пробный период"
