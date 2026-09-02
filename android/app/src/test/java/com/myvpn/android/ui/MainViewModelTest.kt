package com.myvpn.android.ui

import com.myvpn.android.data.VpnAccessResponse
import com.myvpn.android.data.VpnAccessSource
import com.myvpn.android.vpn.VpnConnectionState
import com.myvpn.android.vpn.VpnEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun permissionDenialCanBeRetriedAndNeverStartsService() = runTest {
        val engine = FakeEngine()
        val vm = MainViewModel(ReadySource, engine)
        advanceUntilIdle()
        vm.connect(); assertEquals(MainUiState.AwaitingPermission, vm.state.value)
        vm.onPermissionResult(false)
        assertReadyWithMessage(vm, "VPN permission was denied")
        assertEquals(0, engine.starts)
        vm.connect(); assertEquals(MainUiState.AwaitingPermission, vm.state.value)
    }

    @Test fun grantConnectsOnlyAfterServiceReportsRunningAndDisconnects() = runTest {
        val engine = FakeEngine()
        val vm = MainViewModel(ReadySource, engine)
        advanceUntilIdle()
        vm.connect(); vm.onPermissionResult(true); advanceUntilIdle()
        assertEquals(1, engine.starts); assertEquals(MainUiState.Connecting, vm.state.value)
        engine.report(VpnConnectionState.Connected); advanceUntilIdle()
        assertEquals(MainUiState.Connected, vm.state.value)
        vm.disconnect(); advanceUntilIdle(); assertEquals(1, engine.stops)
        assertEquals(MainUiState.Disconnecting, vm.state.value)
        engine.report(VpnConnectionState.Disconnected); advanceUntilIdle()
        assertTrue(vm.state.value is MainUiState.Ready)
    }

    @Test fun startupFailureLeavesControlledReadyState() = runTest {
        val engine = FakeEngine(failStart = true)
        val vm = MainViewModel(ReadySource, engine)
        advanceUntilIdle(); vm.connect(); vm.onPermissionResult(true); advanceUntilIdle()
        assertReadyWithMessage(vm, "VPN connection could not be started")
    }

    private fun assertReadyWithMessage(vm: MainViewModel, message: String) {
        val state = vm.state.value as MainUiState.Ready
        assertEquals(message, state.message)
    }

    private class FakeEngine(private val failStart: Boolean = false) : VpnEngine {
        private val mutable = MutableStateFlow<VpnConnectionState>(VpnConnectionState.Disconnected)
        override val state: StateFlow<VpnConnectionState> = mutable
        var starts = 0; var stops = 0
        override suspend fun start(configuration: String) { starts++; if (failStart) error("native failure") }
        override suspend fun stop() { stops++ }
        fun report(state: VpnConnectionState) { mutable.value = state }
    }

    private object ReadySource : VpnAccessSource {
        override suspend fun current() = VpnAccessResponse("READY", "FREE", CONFIG)
    }

    private companion object { const val CONFIG = "vless://123e4567-e89b-12d3-a456-426614174000@vpn.example.test:443?type=tcp&security=reality&encryption=none&sni=s&fp=chrome&pbk=p&sid=a" }
}
