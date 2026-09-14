package com.myvpn.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.myvpn.android.vpn.VpnAppsMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AppSelectionScreen(state: AppSelectionState, vm: AppSelectionViewModel) {
    BackHandler { if (!state.busy) vm.back() }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Приложения через VPN", style = MaterialTheme.typography.headlineSmall)
        OutlinedButton(onClick = vm::back, enabled = !state.busy) { Text("Назад") }
        listOf(VpnAppsMode.ALL to "Все приложения", VpnAppsMode.SELECTED to "Только выбранные").forEach { (mode, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = state.draft.mode == mode, enabled = !state.busy, onClick = { vm.mode(mode) })
                Text(label)
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = vm::open) { Text("Повторить") }
        }
        if (state.draft.mode == VpnAppsMode.SELECTED) {
            OutlinedTextField(value = state.query, onValueChange = vm::search, label = { Text("Поиск") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            LazyColumn(Modifier.weight(1f)) {
                items(state.visibleApps, key = { it.packageName }) { app ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = app.packageName in state.draft.packageNames, enabled = !state.busy, onCheckedChange = { vm.toggle(app.packageName) })
                        AppIcon(app.packageName)
                        Text(app.label, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    }
                }
            }
        } else Spacer(Modifier.weight(1f))
        Button(onClick = vm::save, enabled = state.canSave, modifier = Modifier.fillMaxWidth()) { Text("Сохранить") }
    }
}

@Composable
private fun AppIcon(packageName: String) {
    val manager = LocalContext.current.packageManager
    val bitmap by produceState<android.graphics.Bitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { manager.getApplicationIcon(packageName).toBitmap(48, 48) }.getOrNull()
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(32.dp)) }
        ?: Spacer(Modifier.size(32.dp))
}
