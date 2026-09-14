package com.myvpn.android.ui

import com.myvpn.android.data.*
import com.myvpn.android.vpn.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class AppSelectionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = Store()
    private fun vm() = AppSelectionViewModel(store, LaunchableAppsSource { listOf(InstalledApp("chrome", "Chrome")) }, dispatcher)
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain(); VpnConnectionStore.update(VpnConnectionState.Disconnected) }
    @Test fun openDefaultsToAll() = runTest {
        val vm = vm(); vm.open(); advanceUntilIdle()
        assertTrue(vm.state.value.open); assertEquals(VpnAppsMode.ALL, vm.state.value.draft.mode)
        assertTrue(vm.state.value.canSave)
    }
    @Test fun emptySelectedDisablesSave() = runTest {
        val vm = vm(); vm.open(); advanceUntilIdle(); vm.mode(VpnAppsMode.SELECTED); vm.save(); advanceUntilIdle()
        assertFalse(vm.state.value.canSave); assertEquals(0, store.saves)
    }
    @Test fun selectionUpdatesOnlyDraft() = runTest {
        val vm = vm(); vm.open(); advanceUntilIdle(); vm.mode(VpnAppsMode.SELECTED); vm.toggle("chrome")
        assertEquals(setOf("chrome"), vm.state.value.draft.packageNames)
        assertTrue(vm.state.value.canSave); assertEquals(VpnAppSelection(), store.value)
    }
    @Test fun backDiscardsDraft() = runTest {
        val vm = vm(); vm.open(); advanceUntilIdle(); vm.mode(VpnAppsMode.SELECTED); vm.toggle("chrome"); vm.back()
        assertFalse(vm.state.value.open); assertEquals(0, store.saves)
        vm.open(); advanceUntilIdle(); assertEquals(VpnAppSelection(), vm.state.value.draft)
    }
    @Test fun savePersistsAndCloses() = runTest {
        val vm = vm(); vm.open(); advanceUntilIdle(); vm.mode(VpnAppsMode.SELECTED); vm.toggle("chrome"); vm.save(); vm.save(); advanceUntilIdle()
        assertFalse(vm.state.value.open); assertEquals(1, store.saves)
        assertEquals(VpnAppSelection(VpnAppsMode.SELECTED, setOf("chrome")), store.value)
    }
    @Test fun saveWhileConnectedLeavesVpnUntouched() = runTest {
        VpnConnectionStore.update(VpnConnectionState.Connected)
        val vm = vm(); vm.open(); advanceUntilIdle(); vm.save(); advanceUntilIdle()
        assertEquals(VpnConnectionState.Connected, VpnConnectionStore.state.value)
        assertEquals("Изменения применятся при следующем подключении VPN", vm.state.value.savedMessage)
        // ViewModel has no engine/service dependency: save cannot start or stop it.
    }
    @Test fun recreationRestoresSelected() = runTest {
        store.value = VpnAppSelection(VpnAppsMode.SELECTED, setOf("chrome"))
        val first = vm(); first.open(); advanceUntilIdle(); first.back()
        val second = vm(); second.open(); advanceUntilIdle()
        assertEquals(store.value, second.state.value.draft)
    }
    @Test fun backWhileLoadingCannotReopenScreen() = runTest {
        val vm = vm(); vm.open(); vm.back(); advanceUntilIdle(); assertFalse(vm.state.value.open)
    }
    private class Store : VpnAppSelectionSource {
        var value = VpnAppSelection(); var saves = 0
        override fun get() = value
        override fun save(selection: VpnAppSelection) { value = selection; saves++ }
    }
}
