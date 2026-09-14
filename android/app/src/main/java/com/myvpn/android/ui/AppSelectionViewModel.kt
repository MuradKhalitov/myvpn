package com.myvpn.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myvpn.android.data.*
import com.myvpn.android.vpn.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

data class AppSelectionState(
    val open: Boolean = false,
    val busy: Boolean = false,
    val draft: VpnAppSelection = VpnAppSelection(),
    val apps: List<InstalledApp> = emptyList(),
    val query: String = "",
    val error: String? = null,
    val savedMessage: String? = null
) {
    val canSave get() = !busy && error == null &&
        (draft.mode == VpnAppsMode.ALL || draft.packageNames.isNotEmpty())
    val visibleApps get() = filterLaunchableApps(apps, query)
}

class AppSelectionViewModel(
    private val store: VpnAppSelectionSource,
    private val apps: LaunchableAppsSource,
    private val io: CoroutineContext = Dispatchers.IO
) : ViewModel() {
    private val mutable = MutableStateFlow(AppSelectionState())
    val state = mutable.asStateFlow()
    private var generation = 0

    fun open() {
        val attempt = ++generation
        mutable.value = AppSelectionState(open = true, busy = true)
        viewModelScope.launch {
            try {
                val loaded = withContext(io) { store.get() to apps.get() }
                if (generation == attempt) mutable.value = AppSelectionState(open = true, draft = loaded.first, apps = loaded.second)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (generation == attempt) mutable.value = AppSelectionState(open = true, error = "Не удалось загрузить список приложений. Повторите попытку.")
            }
        }
    }

    fun back() { generation++; mutable.value = AppSelectionState() }
    fun mode(mode: VpnAppsMode) { if (!mutable.value.busy) mutable.value = mutable.value.copy(draft = mutable.value.draft.copy(mode = mode)) }
    fun search(query: String) { mutable.value = mutable.value.copy(query = query) }
    fun toggle(packageName: String) {
        val current = mutable.value
        if (current.busy || current.apps.none { it.packageName == packageName }) return
        val packages = current.draft.packageNames
        mutable.value = current.copy(draft = current.draft.copy(packageNames =
            if (packageName in packages) packages - packageName else packages + packageName))
    }

    fun save() {
        val current = mutable.value
        if (!current.open || !current.canSave) return
        val attempt = generation
        mutable.value = current.copy(busy = true)
        viewModelScope.launch {
            try {
                withContext(io) { store.save(current.draft) }
                if (attempt == generation) mutable.value = AppSelectionState(
                    savedMessage = "Изменения применятся при следующем подключении VPN")
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (attempt == generation) mutable.value = current.copy(error = "Не удалось сохранить настройки. Повторите попытку.")
            }
        }
    }
}
