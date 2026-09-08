package com.myvpn.android.data

import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneAuthRepositoryTest {

    @Test fun expiredAccessRestoresFromPersistedRefreshAndRotatesIt() = runTest {
        val store = FakeStore(expiredSession())
        val api = FakeApi()
        val restored = PhoneAuthRepository(api, store).restoreSession()

        assertEquals("new-access", restored?.accessToken)
        assertEquals("new-refresh", restored?.refreshToken)
        assertEquals(1, api.refreshCalls)
        assertEquals("new-refresh", store.value?.refreshToken)
    }

    @Test fun explicitlyRevokedRefreshClearsSessionAndReturnsNoSession() = runTest {
        val store = FakeStore(expiredSession())
        val api = FakeApi(refreshFailure = unauthorized("SESSION_REVOKED"))

        assertNull(PhoneAuthRepository(api, store).restoreSession())
        assertTrue(store.cleared)
        assertEquals(SessionClearReason.REVOKED_SESSION, store.clearReason)
    }

    @Test fun invalidRefreshPreservesSessionForRecovery() = runTest {
        for (code in listOf("REFRESH_TOKEN_INVALID", "REFRESH_TOKEN_REVOKED")) {
        val store = FakeStore(expiredSession())
        val failure = runCatching { PhoneAuthRepository(FakeApi(refreshFailure = unauthorized(code)), store).restoreSession() }.exceptionOrNull()
        assertTrue(failure is SessionRecoveryException)
        assertSame(store.initial, store.value)
        assertTrue(!store.cleared)
        }
    }

    @Test fun genericUnauthorizedFromRefreshDoesNotProveInvalidSession() = runTest {
        val store = FakeStore(expiredSession())
        val api = FakeApi(refreshFailure = unauthorized("INVALID_AUTHENTICATION"))

        val failure = runCatching { PhoneAuthRepository(api, store).restoreSession() }.exceptionOrNull()

        assertTrue(failure is SessionRefreshUnavailableException)
        assertTrue(!store.cleared)
        assertSame(store.initial, store.value)
    }

    @Test fun missingAccessWithPersistedRefreshRestoresWithoutPhoneEntry() = runTest {
        val store = FakeStore(Session("", "old-refresh", 900, "account"))

        val restored = PhoneAuthRepository(FakeApi(), store).restoreSession()

        assertEquals("new-access", restored?.accessToken)
        assertEquals(1, store.saveCalls)
    }

    @Test fun transientRefreshFailureKeepsEncryptedSessionForRetry() = runTest {
        val store = FakeStore(expiredSession())
        val api = FakeApi(refreshFailure = java.io.IOException("offline"))

        val failure = runCatching { PhoneAuthRepository(api, store).restoreSession() }.exceptionOrNull()

        assertTrue(failure is SessionRefreshUnavailableException)
        assertSame(store.initial, store.value)
        assertTrue(!store.cleared)
    }

    @Test fun dnsFailureKeepsSessionForRetry() = runTest {
        val store = FakeStore(expiredSession())

        val failure = runCatching {
            PhoneAuthRepository(FakeApi(refreshFailure = java.net.UnknownHostException("dns")), store).restoreSession()
        }.exceptionOrNull()

        assertTrue(failure is SessionRefreshUnavailableException)
        assertTrue(!store.cleared)
    }

    @Test fun http500And429KeepSessionForRetry() = runTest {
        for (status in listOf(408, 429, 500, 502, 503, 504)) {
            val store = FakeStore(expiredSession())
            val failure = runCatching {
                PhoneAuthRepository(FakeApi(refreshFailure = http(status)), store).restoreSession()
            }.exceptionOrNull()
            assertTrue(failure is SessionRefreshUnavailableException)
            assertTrue(!store.cleared)
            assertSame(store.initial, store.value)
        }
    }

    @Test fun retryAfterLostRotationResponsePersistsRecoveredCredentials() = runTest {
        val store = FakeStore(expiredSession())
        val api = FakeApi(refreshFailures = ArrayDeque(listOf(java.net.SocketTimeoutException("lost response"))), simulateRotation = true)
        val repository = PhoneAuthRepository(api, store)

        assertTrue(runCatching { repository.restoreSession() }.exceptionOrNull() is SessionRefreshUnavailableException)
        advanceTimeBy(30L * 24 * 60 * 60 * 1000)
        // A recreated process still has A; the server has already committed B.
        val recreated = PhoneAuthRepository(api, store)
        val recovered = recreated.restoreSession()

        assertEquals("new-refresh", recovered?.refreshToken)
        assertEquals(2, api.refreshCalls)
        assertEquals(1, api.rotationCounter)
        assertEquals("READY", VpnAccessRepository(api, recreated).current().status)
        assertEquals(listOf("old-refresh", "old-refresh"), api.refreshRequests)
        assertEquals("new-refresh", store.value?.refreshToken)
        assertTrue(!store.cleared)
    }

    @Test fun processRestartUsesPersistedRotatedRefresh() = runTest {
        val store = FakeStore(expiredSession())
        PhoneAuthRepository(FakeApi(), store).restoreSession()
        store.value = store.value?.copy(accessToken = "", expiresAtMillis = 0)

        val restoredAfterProcessDeath = PhoneAuthRepository(FakeApi(), store).restoreSession()

        assertEquals("new-access", restoredAfterProcessDeath?.accessToken)
        assertTrue(!store.cleared)
    }

    @Test fun lostResponseRetryAfterMonthAuthenticatesUiWithoutPhoneEntry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModels = androidx.lifecycle.ViewModelStore()
        try {
            val store = FakeStore(expiredSession())
            val api = FakeApi(refreshFailures = ArrayDeque(listOf(java.net.SocketTimeoutException("lost response"))), simulateRotation = true)
            val auth = PhoneAuthRepository(api, store)
            val engine = object : com.myvpn.android.vpn.VpnEngine {
                override val state = kotlinx.coroutines.flow.MutableStateFlow<com.myvpn.android.vpn.VpnConnectionState>(com.myvpn.android.vpn.VpnConnectionState.Disconnected)
                override suspend fun start(configuration: String) = Unit
                override suspend fun stop() = Unit
            }
            val vm = com.myvpn.android.ui.MainViewModel(auth, VpnAccessRepository(api, auth), engine)
            viewModels.put("auth", vm)
            val observed = mutableListOf<com.myvpn.android.ui.MainUiState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { observed += it } }
            advanceUntilIdle()
            assertTrue(vm.state.value is com.myvpn.android.ui.MainUiState.Error)
            assertEquals("old-refresh", store.value?.refreshToken)
            advanceTimeBy(30L * 24 * 60 * 60 * 1000)
            vm.retry()
            advanceUntilIdle()
            assertTrue(vm.state.value is com.myvpn.android.ui.MainUiState.Ready)
            assertTrue(observed.none { it is com.myvpn.android.ui.MainUiState.PhoneEntry })
            assertEquals("new-refresh", store.value?.refreshToken)
            assertEquals(1, api.rotationCounter)
            assertEquals(listOf("old-refresh", "old-refresh"), api.refreshRequests)
            assertTrue(!store.cleared)
        } finally {
            viewModels.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun parallelUnauthorizedRequestsPerformOneRefreshAndEachRetriesOnce() = runTest {
        val store = FakeStore(validSession("old-access", "old-refresh"))
        val api = FakeApi(refreshDelayMillis = 10, rejectAccessToken = "old-access")
        val auth = PhoneAuthRepository(api, store)
        auth.restoreSession()
        val access = VpnAccessRepository(api, auth)

        val first = async { access.current() }
        val second = async { access.current() }
        advanceUntilIdle()

        assertEquals("READY", first.await().status)
        assertEquals("READY", second.await().status)
        assertEquals(1, api.refreshCalls)
        assertEquals(4, api.accessCalls.size)
        assertTrue(api.accessCalls.all { it == "Bearer old-access" || it == "Bearer new-access" })
    }

    @Test fun validAccessNeedsNoRefresh() = runTest {
        val api = FakeApi()
        val store = FakeStore(validSession("access", "refresh"))
        assertSame(store.initial, PhoneAuthRepository(api, store).restoreSession())
        assertEquals(0, api.refreshCalls)
    }

    @Test fun parallelStartupRestoresPerformOneRefresh() = runTest {
        val api = FakeApi(refreshDelayMillis = 10)
        val repository = PhoneAuthRepository(api, FakeStore(expiredSession()))
        val first = async { repository.restoreSession() }
        val second = async { repository.restoreSession() }
        assertEquals(first.await(), second.await())
        assertEquals(1, api.refreshCalls)
    }

    @Test fun temporaryLocalReadFailurePreservesCredentials() = runTest {
        val store = FakeStore(expiredSession(), readFailure = java.io.IOException("storage unavailable"))
        assertTrue(runCatching { PhoneAuthRepository(FakeApi(), store).restoreSession() }.exceptionOrNull() is java.io.IOException)
        assertSame(store.initial, store.value)
        assertTrue(!store.cleared)
    }

    @Test fun invalidResponseReconcilesNewerPersistedCredential() = runTest {
        val store = FakeStore(expiredSession())
        val api = FakeApi(refreshFailure = unauthorized("REFRESH_TOKEN_INVALID"), onRefresh = {
            store.value = validSession("reconciled-access", "reconciled-refresh")
        })
        assertEquals("reconciled-access", PhoneAuthRepository(api, store).restoreSession()?.accessToken)
        assertEquals(1, api.refreshCalls)
        assertTrue(!store.cleared)
    }

    @Test fun missingAndCorruptedCredentialsAreTheOnlyLocalReasonsForPhoneEntry() = runTest {
        assertNull(PhoneAuthRepository(FakeApi(), FakeStore(null)).restoreSession())
        val empty = FakeStore(validSession("access", ""))
        assertNull(PhoneAuthRepository(FakeApi(), empty).restoreSession())
        assertEquals(SessionClearReason.CORRUPTED_LOCAL_CREDENTIAL, empty.clearReason)
        val damaged = FakeStore(expiredSession(), readFailure = CorruptedLocalCredentialException(IllegalArgumentException()))
        assertNull(PhoneAuthRepository(FakeApi(), damaged).restoreSession())
        assertEquals(SessionClearReason.CORRUPTED_LOCAL_CREDENTIAL, damaged.clearReason)
    }

    @Test fun failedPersistenceDoesNotPublishRotatedSessionInMemory() = runTest {
        val store = FakeStore(expiredSession(), saveFailure = java.io.IOException("disk unavailable"))
        val api = FakeApi()
        val auth = PhoneAuthRepository(api, store)
        repeat(2) { assertTrue(runCatching { auth.restoreSession() }.exceptionOrNull() is SessionRefreshUnavailableException) }
        assertEquals(listOf("old-refresh", "old-refresh"), api.refreshRequests)
        assertSame(store.initial, store.value)
    }

    @Test fun repeatedProtectedUnauthorizedRetriesOnlyOnceAndPreservesSession() = runTest {
        for (status in listOf(401, 403)) {
            val store = FakeStore(validSession("old-access", "old-refresh"))
            val api = FakeApi(accessFailureStatus = status)
            val access = VpnAccessRepository(api, PhoneAuthRepository(api, store))
            assertTrue(runCatching { access.current() }.exceptionOrNull() is HttpException)
            assertEquals(1, api.refreshCalls)
            assertEquals(2, api.accessCalls.size)
            assertTrue(!store.cleared)
        }
    }

    private fun expiredSession() = Session("old-access", "old-refresh", 0, "account", expiresAtMillis = 0)
    private fun validSession(access: String, refresh: String) = Session(access, refresh, 3_600, "account")
    private fun unauthorized(code: String) = HttpException(Response.error<AuthResponse>(401, "{\"code\":\"$code\"}".toResponseBody("application/json".toMediaType())))
    private fun http(status: Int) = HttpException(Response.error<AuthResponse>(status, "{}".toResponseBody("application/json".toMediaType())))

    private class FakeStore(initialValue: Session?, private val readFailure: Throwable? = null, private val saveFailure: Throwable? = null) : SessionStore {
        val initial = initialValue
        var value = initialValue
        var cleared = false
        var clearReason: SessionClearReason? = null
        var saveCalls = 0
        override suspend fun session(): Session? { readFailure?.let { throw it }; return value }
        override suspend fun save(session: Session) { saveFailure?.let { throw it }; saveCalls++; value = session }
        override suspend fun clear(reason: SessionClearReason) { cleared = true; clearReason = reason; value = null }
    }

    private class FakeApi(
        private val refreshFailure: Throwable? = null,
        private val refreshFailures: ArrayDeque<Throwable> = ArrayDeque(),
        private val refreshDelayMillis: Long = 0,
        private val rejectAccessToken: String? = null,
        private val accessFailureStatus: Int? = null,
        private val onRefresh: () -> Unit = {},
        private val simulateRotation: Boolean = false
    ) : MyVpnApi {
        var refreshCalls = 0
        var rotationCounter = 0
        private var currentRefresh = "old-refresh"
        private var previousRefresh: String? = null
        val refreshRequests = mutableListOf<String>()
        val accessCalls = mutableListOf<String>()
        override suspend fun register(request: DeviceRegisterRequest) = auth()
        override suspend fun refresh(request: RefreshRequest): RefreshResponse {
            refreshCalls++
            refreshRequests += request.refreshToken
            onRefresh()
            if (simulateRotation) {
                when (request.refreshToken) {
                    currentRefresh -> {
                        previousRefresh = currentRefresh
                        rotationCounter++
                        currentRefresh = if (rotationCounter == 1) "new-refresh" else "refresh-$rotationCounter"
                    }
                    previousRefresh -> Unit
                    else -> error("Unexpected credential generation")
                }
            }
            if (refreshFailures.isNotEmpty()) throw refreshFailures.removeFirst()
            refreshFailure?.let { throw it }
            if (refreshDelayMillis > 0) delay(refreshDelayMillis)
            return RefreshResponse("new-access", if (simulateRotation) currentRefresh else "new-refresh", expiresIn = 900)
        }
        override suspend fun startPhone(request: PhoneVerificationStartRequest) = PhoneVerificationStartResponse("verification", "+79990000000", "+7 999 000-00-00", "2026-10-01T00:00:00Z")
        override suspend fun phoneStatus(verificationId: String) = PhoneVerificationStatusResponse("PENDING")
        override suspend fun exchangePhone(request: PhoneVerificationExchangeRequest) = PhoneAuthResponse("account", "new-access", "new-refresh", expiresIn = 900, accessStatus = "TRIAL")
        override suspend fun appVersion() = AppVersionResponse(1, "1.0.0", 1, "https://example.test/app.apk", "")
        override suspend fun access(bearer: String): VpnAccessResponse {
            accessCalls += bearer
            accessFailureStatus?.let { throw HttpException(Response.error<VpnAccessResponse>(it, "{}".toResponseBody("application/json".toMediaType()))) }
            if (bearer == "Bearer $rejectAccessToken") {
                throw HttpException(Response.error<VpnAccessResponse>(401, "{}".toResponseBody("application/json".toMediaType())))
            }
            return VpnAccessResponse("READY", "TRIAL", "config")
        }
        private fun auth() = AuthResponse("account", "new-access", "new-refresh", expiresIn = 900)
    }
}
