package com.myvpn.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myvpn.android.vpn.VpnConnectionState

@Composable
internal fun TariffScreen(state: MainUiState.Tariffs, vm: MainViewModel) {
    BackHandler(onBack = vm::closeTariffs)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text("Выберите тариф", style = MaterialTheme.typography.headlineSmall)
        OutlinedButton(onClick = vm::closeTariffs, enabled = !state.busy) { Text("Назад") }
        when (state.connection) {
            VpnConnectionState.Connected, VpnConnectionState.Connecting -> {
                Text(if (state.connection == VpnConnectionState.Connected) "VPN подключён" else "Подключаем VPN")
                OutlinedButton(onClick = { vm.disconnect() }) { Text("Отключить VPN") }
            }
            VpnConnectionState.Disconnecting -> Text("Отключаем VPN")
            else -> Unit
        }
        if (state.loading || state.busy) CircularProgressIndicator()
        state.message?.let { Text(it, modifier = Modifier.padding(vertical = 12.dp)) }
        state.items.forEach { tariff ->
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(tariff.name, style = MaterialTheme.typography.titleLarge)
                    Text("${tariff.durationDays} дней")
                    tariff.description?.takeIf { it.isNotBlank() }?.let { Text(it) }
                    Text("${tariff.price.toPlainString()} ${tariff.currency}", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { vm.chooseTariff(tariff.code) },
                        enabled = !state.loading && !state.busy && !state.awaitingPayment) { Text("Выбрать") }
                }
            }
        }
        if (!state.loading && state.items.isEmpty()) {
            Button(onClick = vm::openTariffs, enabled = !state.busy) { Text("Повторить") }
        }
    }
}
