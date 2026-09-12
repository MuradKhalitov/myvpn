package com.myvpn.android.ui

import com.myvpn.android.data.PhoneAuthSource
import com.myvpn.android.data.PhoneVerificationStartResponse
import com.myvpn.android.data.PhoneVerificationStatusResponse
import com.myvpn.android.data.Session
import com.myvpn.android.data.SessionExpiredException
import com.myvpn.android.data.SessionRefreshUnavailableException
import com.myvpn.android.data.VpnAccessResponse
import com.myvpn.android.data.VpnAccessSource
import com.myvpn.android.vpn.VpnConnectionState
import com.myvpn.android.vpn.VpnEngine
import java.time.Instant
import java.io.IOException
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

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

    @Test fun serviceAlreadyConnectedRestoresConnectedUiAfterViewModelInit() = runTest {
        val engine = FakeEngine(VpnConnectionState.Connected)

        val vm = viewModel(FakeAuth(restored = session()), FakeAccess(VpnAccessResponse("READY", "TRIAL", CONFIG)), engine)
        advanceUntilIdle()

        assertTrue(vm.state.value is MainUiState.Connected)
    }

    @Test fun inactiveMyVpnServiceRestoresDisconnectedUi() = runTest {
        val vm = viewModel(FakeAuth(restored = session()), FakeAccess(VpnAccessResponse("READY", "TRIAL", CONFIG)), FakeEngine())
        advanceUntilIdle()
        assertTrue(vm.state.value is MainUiState.Ready)
    }

    @Test fun recreatedUiUsesConnectedStateOwnedBySurvivingService() = runTest {
        val engine = FakeEngine(VpnConnectionState.Connected)
        val first = viewModel(FakeAuth(restored = session()), FakeAccess(VpnAccessResponse("READY", "TRIAL", CONFIG)), engine)
        advanceUntilIdle()
        assertTrue(first.state.value is MainUiState.Connected)

        val recreated = viewModel(FakeAuth(restored = session()), FakeAccess(VpnAccessResponse("READY", "TRIAL", CONFIG)), engine)
        advanceUntilIdle()
        assertTrue(recreated.state.value is MainUiState.Connected)
    }

    @Test fun connectIsIdempotentWhenServiceIsAlreadyConnected() = runTest {
        val engine = FakeEngine(VpnConnectionState.Connected)
        val vm = viewModel(FakeAuth(restored = session()), FakeAccess(VpnAccessResponse("READY", "TRIAL", CONFIG)), engine)
        advanceUntilIdle()

        vm.connect()
        vm.onPermissionResult(true)
        advanceUntilIdle()

        assertEquals(0, engine.startCalls)
        assertTrue(vm.state.value is MainUiState.Connected)
    }

    @Test fun restoredConnectedTunnelCanBeDisconnected() = runTest {
        val engine = FakeEngine(VpnConnectionState.Connected)
        val vm = viewModel(FakeAuth(restored = session()), FakeAccess(VpnAccessResponse("READY", "TRIAL", CONFIG)), engine)
        advanceUntilIdle()

        vm.disconnect()
        advanceUntilIdle()

        assertEquals(1, engine.stopCalls)
        assertTrue(vm.state.value is MainUiState.Ready)
    }

    @Test fun uiFollowsServiceConnectingConnectedAndDeathTransitions() = runTest {
        val engine = FakeEngine()
        val vm = viewModel(FakeAuth(restored = session()), FakeAccess(VpnAccessResponse("READY", "TRIAL", CONFIG)), engine)
        advanceUntilIdle()

        engine.emit(VpnConnectionState.Connecting); runCurrent()
        assertTrue(vm.state.value is MainUiState.Connecting)
        engine.emit(VpnConnectionState.Connected); runCurrent()
        assertTrue(vm.state.value is MainUiState.Connected)
        engine.emit(VpnConnectionState.Disconnected); runCurrent()
        assertTrue(vm.state.value is MainUiState.Ready)
    }

    @Test fun foreignVpnDoesNotAffectDisconnectedMyVpnState() = runTest {
        // No ConnectivityManager signal participates in MyVPN state; only its service state does.
        val engine = FakeEngine(VpnConnectionState.Disconnected)
        val vm = viewModel(FakeAuth(restored = session()), FakeAccess(VpnAccessResponse("READY", "TRIAL", CONFIG)), engine)
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

    @Test fun confirmedRevokedSessionReturnsPhoneEntry() = runTest {
        val auth = FakeAuth(restored = null)
        val vm = viewModel(auth, FakeAccess())
        advanceUntilIdle()
        assertTrue(vm.state.value is MainUiState.PhoneEntry)
    }

    @Test fun retryAfterTransientRefreshFailureRestoresWithoutPhoneEntry() = runTest {
        val auth = FakeAuth(
            restored = session(),
            restoreFailures = mutableListOf(SessionRefreshUnavailableException(IOException("offline"))))
        val vm = viewModel(auth, FakeAccess(VpnAccessResponse("READY", "TRIAL", CONFIG)))
        advanceUntilIdle()
        assertEquals(AppError.NETWORK_UNAVAILABLE, (vm.state.value as MainUiState.Error).type)

        vm.retry()
        advanceUntilIdle()

        assertTrue(vm.state.value is MainUiState.Ready)
        assertEquals(2, auth.restoreCalls)
    }

    @Test fun invalidRefreshShowsSessionErrorAndRetryRestoresWithoutPhoneEntry() = runTest {
        val auth = FakeAuth(restored = session(), restoreFailures = mutableListOf(
            com.myvpn.android.data.SessionRecoveryException(IllegalStateException())))
        val vm = viewModel(auth, FakeAccess(VpnAccessResponse("READY", "TRIAL", CONFIG)))
        advanceUntilIdle()
        assertEquals(AppError.SESSION_RECOVERY_FAILED, (vm.state.value as MainUiState.Error).type)
        assertEquals("Не удалось восстановить сессию", (vm.state.value as MainUiState.Error).message)
        vm.retry()
        advanceUntilIdle()
        assertTrue(vm.state.value is MainUiState.Ready)
        assertEquals(2, auth.restoreCalls)
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

    @Test fun samePhoneReusesPendingVerificationAfterChangingNumber() = runTest {
        val auth = FakeAuth(restored = null)
        val vm = startVerification(auth)
        val original = (vm.state.value as MainUiState.PhoneVerification).verification
        vm.changePhoneNumber()
        vm.startPhoneVerification("+7 (999) 123-45-67")
        advanceUntilIdle()

        val reused = (vm.state.value as MainUiState.PhoneVerification).verification
        assertEquals(1, auth.starts)
        assertEquals(original.verificationId, reused.verificationId)
        assertEquals(original.callPhone, reused.callPhone)
        assertEquals(original.expiresAt, reused.expiresAt)
    }

    @Test fun formattedAndPastedFormsOfSamePhoneReusePendingVerification() = runTest {
        val auth = FakeAuth(restored = null)
        val vm = viewModel(auth, FakeAccess())
        advanceUntilIdle()
        vm.startPhoneVerification("9633751002")
        advanceUntilIdle()
        listOf("89633751002", "+7 (963) 375-10-02", "9633751002").forEach { input ->
            vm.changePhoneNumber()
            vm.startPhoneVerification(input)
            advanceUntilIdle()
        }
        assertEquals(1, auth.starts)
        assertEquals(listOf("+79633751002"), auth.startedPhones)
    }

    @Test fun differentPhoneStartsNewVerification() = runTest {
        val auth = FakeAuth(restored = null)
        val vm = startVerification(auth)
        vm.changePhoneNumber()
        vm.startPhoneVerification("+7 963 375-10-02")
        advanceUntilIdle()
        assertEquals(2, auth.starts)
        assertEquals(listOf("+79991234567", "+79633751002"), auth.startedPhones)
    }

    @Test fun expiredPendingVerificationIsNotReused() = runTest {
        val expired = PhoneVerificationStartResponse("old", "+79990000000", "+7 999 000-00-00", "2026-08-01T00:00:00Z")
        val auth = FakeAuth(restored = null, started = expired)
        val vm = viewModel(auth, FakeAccess())
        advanceUntilIdle()
        vm.startPhoneVerification("+79990000000")
        advanceUntilIdle()
        vm.changePhoneNumber()
        vm.startPhoneVerification("+79990000000")
        advanceUntilIdle()
        assertEquals(2, auth.starts)
    }

    @Test fun reusingVerificationStartsAtMostOneNewPollingJob() = runTest {
        val auth = FakeAuth(restored = null, statuses = mutableListOf(PhoneVerificationStatusResponse("PENDING"), PhoneVerificationStatusResponse("PENDING")))
        val vm = startVerification(auth)
        vm.onReturnedFromDialer(); runCurrent()
        vm.changePhoneNumber()
        vm.startPhoneVerification("+79991234567")
        advanceUntilIdle()
        vm.onReturnedFromDialer(); vm.onReturnedFromDialer(); runCurrent()
        assertEquals(2, auth.statusCalls)
        vm.stopPhoneVerificationPolling()
    }

    @Test fun rejectedPhoneStartHasClearRateLimitOrActiveVerificationMessage() = runTest {
        val auth = FakeAuth(restored = null, startFailure = HttpException(Response.error<PhoneVerificationStartResponse>(401, "{}".toResponseBody("application/json".toMediaType()))))
        val vm = viewModel(auth, FakeAccess())
        advanceUntilIdle()
        vm.startPhoneVerification("+79991234567")
        advanceUntilIdle()
        val error = vm.state.value as MainUiState.Error
        assertEquals(AppError.PHONE_START_REJECTED, error.type)
        assertTrue(error.message.contains("подтвержден"))
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
    @Test fun connectedIsVisibleImmediatelyBeforeAuthAndConfigLoad() = runTest {
        val engine = FakeEngine(VpnConnectionState.Connected)
        val vm = viewModel(FakeAuth(session()), FakeAccess(), engine)
        assertTrue(vm.state.value is MainUiState.Connected)
        advanceUntilIdle()
        val connected = vm.state.value as MainUiState.Connected
        assertEquals(CONFIG, connected.access?.configuration)
        assertEquals(null, connected.message)
        assertEquals(0, engine.startCalls)
    }

    @Test fun connectedSurvivesAccessNetworkFailureAndCanDisconnect() = runTest {
        val engine = FakeEngine(VpnConnectionState.Connected)
        val vm = viewModel(FakeAuth(session()), failingAccess(IOException()), engine)
        advanceUntilIdle()
        val connected = vm.state.value as MainUiState.Connected
        assertEquals(null, connected.access)
        assertTrue(connected.message!!.contains("Нет подключения к сети"))
        vm.connect(); vm.onPermissionResult(true)
        advanceUntilIdle()
        assertEquals(0, engine.startCalls)
        vm.disconnect()
        advanceUntilIdle()
        assertEquals(1, engine.stopCalls)
        assertEquals(AppError.NETWORK_UNAVAILABLE, (vm.state.value as MainUiState.Error).type)
    }

    @Test fun connectedSurvivesAccessServerError() = runTest {
        val failure = HttpException(Response.error<VpnAccessResponse>(503, "{}".toResponseBody("application/json".toMediaType())))
        val vm = viewModel(FakeAuth(session()), failingAccess(failure), FakeEngine(VpnConnectionState.Connected))
        advanceUntilIdle()
        assertTrue((vm.state.value as MainUiState.Connected).message!!.contains("Сервис временно недоступен"))
    }

    @Test fun coldRestartWithRefreshTimeoutStillAllowsStop() = runTest {
        val engine = FakeEngine(VpnConnectionState.Connected)
        val vm = viewModel(FakeAuth(session(), restoreFailures = mutableListOf(
            SessionRefreshUnavailableException(java.net.SocketTimeoutException()))), FakeAccess(), engine)
        advanceUntilIdle()
        assertTrue((vm.state.value as MainUiState.Connected).message!!.contains("Нет подключения к сети"))
        vm.disconnect()
        advanceUntilIdle()
        assertEquals(1, engine.stopCalls)
        assertTrue(vm.state.value is MainUiState.Error)
    }

    @Test fun connectedWithoutRestoredAuthStillAllowsStop() = runTest {
        val engine = FakeEngine(VpnConnectionState.Connected)
        val vm = viewModel(FakeAuth(null), FakeAccess(), engine)
        advanceUntilIdle()
        assertTrue((vm.state.value as MainUiState.Connected).message!!.contains("Войдите"))
        vm.disconnect()
        advanceUntilIdle()
        assertEquals(1, engine.stopCalls)
        assertTrue(vm.state.value is MainUiState.PhoneEntry)
    }

    @Test fun connectedWithProvisioningFailureStillAllowsStop() = runTest {
        val engine = FakeEngine(VpnConnectionState.Connected)
        val vm = viewModel(FakeAuth(session()), FakeAccess(VpnAccessResponse("RETRY_REQUIRED", "TRIAL", null)), engine)
        advanceUntilIdle()
        assertTrue((vm.state.value as MainUiState.Connected).message!!.contains("повторной попытки"))
        vm.disconnect()
        advanceUntilIdle()
        assertEquals(1, engine.stopCalls)
        assertEquals(AppError.VPN_PROVISIONING_FAILED, (vm.state.value as MainUiState.Error).type)
    }

    @Test fun pendingProvisioningDoesNotHideConnected() = runTest {
        val vm = viewModel(FakeAuth(session()), FakeAccess(
            VpnAccessResponse("PROVISIONING", "TRIAL", null)), FakeEngine(VpnConnectionState.Connected))
        runCurrent()
        assertTrue((vm.state.value as MainUiState.Connected).message!!.contains("Настраиваем VPN"))
        advanceUntilIdle()
        assertEquals(null, (vm.state.value as MainUiState.Connected).message)
    }

    @Test fun disconnectedWithBackendErrorCannotStart() = runTest {
        val engine = FakeEngine()
        val vm = viewModel(FakeAuth(session()), failingAccess(IOException()), engine)
        advanceUntilIdle()
        vm.connect(); vm.onPermissionResult(true)
        advanceUntilIdle()
        assertTrue(vm.state.value is MainUiState.Error)
        assertEquals(0, engine.startCalls)
    }

    @Test fun normalReadyConnectAndDisconnectFlowIsPreserved() = runTest {
        val engine = FakeEngine()
        val vm = viewModel(FakeAuth(session()), FakeAccess(), engine)
        advanceUntilIdle()
        assertTrue(vm.state.value is MainUiState.Ready)
        vm.connect(); runCurrent()
        assertTrue(vm.state.value is MainUiState.AwaitingPermission)
        vm.onPermissionResult(true); advanceUntilIdle()
        assertEquals(1, engine.startCalls)
        assertTrue(vm.state.value is MainUiState.Connecting)
        engine.emit(VpnConnectionState.Connected); runCurrent()
        assertEquals(CONFIG, (vm.state.value as MainUiState.Connected).access?.configuration)
        vm.disconnect(); advanceUntilIdle()
        assertEquals(1, engine.stopCalls)
        assertTrue(vm.state.value is MainUiState.Ready)
    }

    @Test fun transitionalEngineStatesSurviveBackendError() = runTest {
        val engine = FakeEngine(VpnConnectionState.Connecting)
        val vm = viewModel(FakeAuth(session()), failingAccess(IOException()), engine)
        assertTrue(vm.state.value is MainUiState.Connecting)
        advanceUntilIdle()
        assertTrue((vm.state.value as MainUiState.Connecting).message!!.contains("Нет подключения"))
        vm.connect(); vm.onPermissionResult(true); runCurrent()
        assertEquals(0, engine.startCalls)
        vm.disconnect(); advanceUntilIdle()
        assertEquals(1, engine.stopCalls)
        val stopping = viewModel(FakeAuth(session()), failingAccess(IOException()), FakeEngine(VpnConnectionState.Disconnecting))
        assertTrue(stopping.state.value is MainUiState.Disconnecting)
        advanceUntilIdle()
        assertTrue((stopping.state.value as MainUiState.Disconnecting).message!!.contains("Нет подключения"))
    }

    @Test fun failedEngineIsNotShownAsActive() = runTest {
        val vm = viewModel(FakeAuth(session()), failingAccess(IOException()), FakeEngine(VpnConnectionState.Failed("failed")))
        advanceUntilIdle()
        assertTrue(vm.state.value is MainUiState.Error)
    }

    @Test fun emptyConfigurationCannotStartNewVpn() = runTest {
        val engine = FakeEngine()
        val vm = viewModel(FakeAuth(session()), FakeAccess(VpnAccessResponse("READY", "TRIAL", "")), engine)
        advanceUntilIdle()
        vm.connect(); vm.onPermissionResult(true); advanceUntilIdle()
        assertEquals(0, engine.startCalls)
        assertTrue(vm.state.value is MainUiState.Ready)
    }

    @Test fun failedRefreshDoesNotReusePreviouslyReadyConfigAfterStop() = runTest {
        var fail = false
        val access = object : VpnAccessSource {
            override suspend fun current(): VpnAccessResponse {
                if (fail) throw IOException()
                return VpnAccessResponse("READY", "TRIAL", CONFIG)
            }
        }
        val engine = FakeEngine(VpnConnectionState.Connected)
        val vm = viewModel(FakeAuth(session()), access, engine)
        advanceUntilIdle()
        fail = true
        vm.retry(); advanceUntilIdle()
        assertTrue(vm.state.value is MainUiState.Connected)
        vm.disconnect(); advanceUntilIdle()
        vm.connect(); vm.onPermissionResult(true); advanceUntilIdle()
        assertTrue(vm.state.value is MainUiState.Error)
        assertEquals(0, engine.startCalls)
    }

    private fun failingAccess(failure: Throwable) = object : VpnAccessSource {
        override suspend fun current(): VpnAccessResponse = throw failure
    }

    private fun viewModel(auth: FakeAuth, access: VpnAccessSource, engine: FakeEngine = FakeEngine()) = MainViewModel(auth, access, engine, 2_000) { Instant.parse("2026-09-01T00:00:00Z") }
    private fun session(accessStatus: String = "TRIAL") = Session("access", "refresh", 3600, "account", accessStatus, "2026-09-10T00:00:00Z")

    private class FakeAuth(
        private val restored: Session?,
        private val statuses: MutableList<PhoneVerificationStatusResponse> = mutableListOf(),
        private val started: PhoneVerificationStartResponse = PhoneVerificationStartResponse("verification", "+79991234567", "+7 999 123-45-67", "2026-09-10T00:00:00Z"),
        private val startFailure: Throwable? = null,
        private val restoreFailures: MutableList<Throwable> = mutableListOf()
    ) : PhoneAuthSource {
        var starts = 0; var statusCalls = 0; var exchanges = 0; var savedSessions = 0; var deviceRegistrationCalls = 0; var restoreCalls = 0
        val startedPhones = mutableListOf<String>()
        override suspend fun restoreSession(): Session? { restoreCalls++; if (restoreFailures.isNotEmpty()) throw restoreFailures.removeAt(0); return restored }
        override suspend fun startVerification(phone: String): PhoneVerificationStartResponse { starts++; startedPhones += phone; startFailure?.let { throw it }; return started }
        override suspend fun verificationStatus(verificationId: String): PhoneVerificationStatusResponse { statusCalls++; return statuses.removeFirstOrNull() ?: PhoneVerificationStatusResponse("PENDING") }
        override suspend fun exchange(verificationId: String, exchangeToken: String): Session { exchanges++; savedSessions++; return Session("access", "refresh", 3600, "account", "TRIAL", "2026-09-10T00:00:00Z") }
    }
    private class FakeAccess(vararg values: VpnAccessResponse) : VpnAccessSource {
        private val queue = values.toMutableList()
        override suspend fun current() = if (queue.isNotEmpty()) queue.removeAt(0) else VpnAccessResponse("READY", "TRIAL", CONFIG)
    }
    private class FakeEngine(initial: VpnConnectionState = VpnConnectionState.Disconnected) : VpnEngine {
        private val mutable = MutableStateFlow(initial)
        override val state: StateFlow<VpnConnectionState> = mutable
        var startCalls = 0
        var stopCalls = 0
        override suspend fun start(configuration: String) { startCalls++; mutable.value = VpnConnectionState.Connecting }
        override suspend fun stop() { stopCalls++; mutable.value = VpnConnectionState.Disconnected }
        fun emit(value: VpnConnectionState) { mutable.value = value }
    }
    private companion object { const val CONFIG = "vless://123e4567-e89b-12d3-a456-426614174000@vpn.example.test:443?type=tcp&security=reality&encryption=none&sni=s&fp=chrome&pbk=p&sid=a" }
}
