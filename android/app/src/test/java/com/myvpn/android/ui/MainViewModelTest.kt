package com.myvpn.android.ui

import com.myvpn.android.data.PhoneAuthSource
import com.myvpn.android.data.PhoneVerificationStartResponse
import com.myvpn.android.data.PhoneVerificationStatusResponse
import com.myvpn.android.data.Session
import com.myvpn.android.data.SessionExpiredException
import com.myvpn.android.data.VpnAccessResponse
import com.myvpn.android.data.VpnAccessSource
import com.myvpn.android.vpn.VpnConnectionState
import com.myvpn.android.vpn.VpnEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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

    @Test fun noTokensStartsAtPhoneEntryWithoutDeviceRegistration() = runTest {
        val auth = FakeAuth(restored = null)
        val vm = viewModel(auth, FakeAccess())
        advanceUntilIdle()
        assertTrue(vm.state.value is MainUiState.PhoneEntry)
        assertEquals(0, auth.deviceRegistrationCalls)
    }

    @Test fun validExistingSessionUsesAuthenticatedVpnFlow() = runTest {
        val auth = FakeAuth(restored = session())
        val vm = viewModel(auth, FakeAccess(VpnAccessResponse("READY", "TRIAL", CONFIG)))
        advanceUntilIdle()
        assertTrue(vm.state.value is MainUiState.Ready)
    }

    @Test fun expiredAccessWithValidRefreshUsesAuthenticatedFlow() = runTest {
        val auth = FakeAuth(restored = session())
        val vm = viewModel(auth, FakeAccess(VpnAccessResponse("READY", "PREMIUM", CONFIG, premiumExpiresAt = "2026-09-10T00:00:00Z")))
        advanceUntilIdle()
        assertTrue(vm.state.value is MainUiState.Ready)
        assertEquals("PREMIUM", (vm.state.value as MainUiState.Ready).access.entitlement)
    }

    @Test fun expiredAccessWithFailedRefreshReturnsPhoneEntry() = runTest {
        val auth = FakeAuth(restored = null)
        val vm = viewModel(auth, FakeAccess())
        advanceUntilIdle()
        assertTrue(vm.state.value is MainUiState.PhoneEntry)
    }

    @Test fun startPhoneVerificationValidatesAndStarts() = runTest {
        val auth = FakeAuth(restored = null)
        val vm = viewModel(auth, FakeAccess())
        advanceUntilIdle()
        vm.startPhoneVerification("bad")
        assertEquals("Введите номер в формате +7 999 123-45-67", (vm.state.value as MainUiState.PhoneEntry).validationMessage)
        vm.startPhoneVerification("+7 999 123-45-67")
        advanceUntilIdle()
        assertEquals(1, auth.starts)
        assertTrue(vm.state.value is MainUiState.PhoneVerification)
    }

    @Test fun pendingVerificationContinuesPolling() = runTest {
        val auth = FakeAuth(restored = null, statuses = mutableListOf(PhoneVerificationStatusResponse("PENDING"), PhoneVerificationStatusResponse("PENDING")))
        val vm = startVerification(auth)
        vm.onReturnedFromDialer(); runCurrent()
        assertEquals(1, auth.statusCalls)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(2, auth.statusCalls)
        assertTrue(vm.state.value is MainUiState.PhoneVerification)
        vm.stopPhoneVerificationPolling()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(2, auth.statusCalls)
    }

    @Test fun verifiedExchangesExactlyOnceAndSavesSession() = runTest {
        val auth = FakeAuth(restored = null, statuses = mutableListOf(PhoneVerificationStatusResponse("VERIFIED", "exchange-token")))
        val vm = startVerification(auth)
        vm.onReturnedFromDialer(); advanceUntilIdle()
        assertEquals(1, auth.exchanges)
        assertEquals(1, auth.savedSessions)
        assertTrue(vm.state.value is MainUiState.Ready)
        vm.onReturnedFromDialer(); advanceUntilIdle()
        assertEquals(1, auth.exchanges)
    }

    @Test fun expiredAndFailedVerificationHaveDedicatedErrors() = runTest {
        val expired = FakeAuth(restored = null, statuses = mutableListOf(PhoneVerificationStatusResponse("EXPIRED")))
        val expiredVm = startVerification(expired); expiredVm.onReturnedFromDialer(); advanceUntilIdle()
        assertEquals(AppError.VERIFICATION_EXPIRED, (expiredVm.state.value as MainUiState.Error).type)
        val failed = FakeAuth(restored = null, statuses = mutableListOf(PhoneVerificationStatusResponse("FAILED")))
        val failedVm = startVerification(failed); failedVm.onReturnedFromDialer(); advanceUntilIdle()
        assertEquals(AppError.VERIFICATION_FAILED, (failedVm.state.value as MainUiState.Error).type)
    }

    @Test fun provisioningPollsUntilReady() = runTest {
        val access = FakeAccess(VpnAccessResponse("PROVISIONING", "TRIAL"), VpnAccessResponse("READY", "TRIAL", CONFIG))
        val vm = viewModel(FakeAuth(restored = session()), access)
        runCurrent(); assertTrue(vm.state.value is MainUiState.VpnProvisioning)
        advanceTimeBy(2_000); runCurrent(); assertTrue(vm.state.value is MainUiState.Ready)
    }

    @Test fun retryRequiredIsDedicatedVpnRecoveryError() = runTest {
        val vm = viewModel(FakeAuth(restored = session()), FakeAccess(VpnAccessResponse("RETRY_REQUIRED", "TRIAL")))
        advanceUntilIdle()
        assertEquals(AppError.VPN_PROVISIONING_FAILED, (vm.state.value as MainUiState.Error).type)
    }

    @Test fun trialPremiumAndExpiredUseDistinctUiStates() = runTest {
        val trial = viewModel(FakeAuth(restored = session(accessStatus = "TRIAL")), FakeAccess(VpnAccessResponse("READY", "TRIAL", CONFIG)))
        advanceUntilIdle(); assertEquals("TRIAL", (trial.state.value as MainUiState.Ready).access.entitlement)
        val premium = viewModel(FakeAuth(restored = session(accessStatus = "PREMIUM")), FakeAccess(VpnAccessResponse("READY", "PREMIUM", CONFIG, premiumExpiresAt = "2026-09-10T00:00:00Z")))
        advanceUntilIdle(); assertEquals("PREMIUM", (premium.state.value as MainUiState.Ready).access.entitlement)
        val expired = viewModel(FakeAuth(restored = session(accessStatus = "EXPIRED")), FakeAccess(VpnAccessResponse("READY", "EXPIRED")))
        advanceUntilIdle(); assertTrue(expired.state.value is MainUiState.Expired)
    }

    @Test fun dialRequestUsesProviderCallPhone() {
        assertEquals(DialRequest("android.intent.action.DIAL", "tel:+79991234567"), dialRequest("+79991234567"))
    }

    private suspend fun TestScope.startVerification(auth: FakeAuth): MainViewModel {
        val vm = viewModel(auth, FakeAccess(VpnAccessResponse("READY", "TRIAL", CONFIG)))
        advanceUntilIdle(); vm.startPhoneVerification("+79991234567"); advanceUntilIdle()
        return vm
    }
    private fun viewModel(auth: FakeAuth, access: FakeAccess) = MainViewModel(auth, access, FakeEngine(), 2_000)
    private fun session(accessStatus: String = "TRIAL") = Session("access", "refresh", 3600, "account", accessStatus, "2026-09-10T00:00:00Z")

    private class FakeAuth(
        private val restored: Session?,
        private val statuses: MutableList<PhoneVerificationStatusResponse> = mutableListOf(),
        private val started: PhoneVerificationStartResponse = PhoneVerificationStartResponse("verification", "+79991234567", "+7 999 123-45-67", "2026-09-10T00:00:00Z")
    ) : PhoneAuthSource {
        var starts = 0; var statusCalls = 0; var exchanges = 0; var savedSessions = 0; var deviceRegistrationCalls = 0
        override suspend fun restoreSession() = restored
        override suspend fun startVerification(phone: String): PhoneVerificationStartResponse { starts++; return started }
        override suspend fun verificationStatus(verificationId: String): PhoneVerificationStatusResponse { statusCalls++; return statuses.removeFirstOrNull() ?: PhoneVerificationStatusResponse("PENDING") }
        override suspend fun exchange(verificationId: String, exchangeToken: String): Session { exchanges++; savedSessions++; return Session("access", "refresh", 3600, "account", "TRIAL", "2026-09-10T00:00:00Z") }
    }
    private class FakeAccess(vararg values: VpnAccessResponse) : VpnAccessSource {
        private val queue = values.toMutableList()
        override suspend fun current() = if (queue.isNotEmpty()) queue.removeAt(0) else VpnAccessResponse("READY", "TRIAL", CONFIG)
    }
    private class FakeEngine : VpnEngine {
        private val mutable = MutableStateFlow<VpnConnectionState>(VpnConnectionState.Disconnected)
        override val state: StateFlow<VpnConnectionState> = mutable
        override suspend fun start(configuration: String) { mutable.value = VpnConnectionState.Connecting }
        override suspend fun stop() { mutable.value = VpnConnectionState.Disconnecting }
    }
    private companion object { const val CONFIG = "vless://123e4567-e89b-12d3-a456-426614174000@vpn.example.test:443?type=tcp&security=reality&encryption=none&sni=s&fp=chrome&pbk=p&sid=a" }
}
