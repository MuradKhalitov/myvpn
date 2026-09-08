package com.myvpn.android.data

import androidx.lifecycle.ViewModelStore
import com.myvpn.android.ui.MainUiState
import com.myvpn.android.ui.MainViewModel
import com.myvpn.android.ui.AppError
import com.myvpn.android.vpn.VpnConnectionState
import com.myvpn.android.vpn.VpnEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import java.util.concurrent.Executors
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises HTTP and the real Retrofit serializer, rather than returning a prebuilt DTO. */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshWireContractTest {
    @Test fun successfulBackendRefreshWithoutAccountIdRestoresPersistedSession() = runBlocking {
        val server = MockWebServer()
        val fixture = javaClass.getResourceAsStream("/refresh-success.json")!!.bufferedReader().use { it.readText() }
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(fixture))
        server.start()
        try {
            val api = ApiFactory.create(server.url("/").toString())
            val store = WireStore()
            val result = PhoneAuthRepository(api, store).restoreSession()
            assertEquals("access-b", result?.accessToken)
            assertEquals("credential-b", store.saved?.refreshToken)
            assertEquals("existing-account", result?.accountId)
            assertEquals(1, server.requestCount)
            assertFalse(store.cleared)
        } finally {
            server.shutdown()
        }
    }

    @Test fun missingAccessStillUsesRefreshWireContract() = runBlocking {
        val server = MockWebServer()
        server.enqueue(success())
        server.start()
        try {
            val store = WireStore().apply { saved = saved!!.copy(accessToken = "", expiresAtMillis = Long.MAX_VALUE) }
            assertEquals("access-b", PhoneAuthRepository(ApiFactory.create(server.url("/").toString()), store).restoreSession()?.accessToken)
            assertFalse(store.cleared)
        } finally { server.shutdown() }
    }

    @Test fun http500RetryMakesSecondRefreshAndReachesReady() = retryToReady(
        MockResponse().setResponseCode(500).setBody("{}"), AppError.BACKEND_UNAVAILABLE)

    @Test fun malformed200IsResponseFormatErrorAndRetryActuallyRecovers() = retryToReady(
        MockResponse().setResponseCode(200).setBody("{}"), AppError.RESPONSE_FORMAT)

    @Test fun invalidCredentialIsPreservedAndRetryMakesAnotherRequest() = retryToReady(
        MockResponse().setResponseCode(401).setBody("{\"code\":\"REFRESH_TOKEN_INVALID\"}"), AppError.SESSION_RECOVERY_FAILED)

    private fun retryToReady(firstResponse: MockResponse, expectedError: AppError) = runBlocking {
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val viewModels = ViewModelStore()
        val server = MockWebServer()
        server.enqueue(firstResponse.setHeader("Content-Type", "application/json"))
        server.enqueue(success())
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("{\"status\":\"READY\",\"entitlement\":\"TRIAL\",\"configuration\":null}"))
        server.start()
        var observer: Job? = null
        try {
            val store = WireStore()
            val api = ApiFactory.create(server.url("/").toString())
            val auth = PhoneAuthRepository(api, store)
            val engine = object : VpnEngine {
                override val state = MutableStateFlow<VpnConnectionState>(VpnConnectionState.Disconnected)
                override suspend fun start(configuration: String) = Unit
                override suspend fun stop() = Unit
            }
            val vm = withContext(main) { MainViewModel(auth, VpnAccessRepository(api, auth), engine) }
            viewModels.put("auth", vm)
            var sawPhoneEntry = false
            observer = launch(start = CoroutineStart.UNDISPATCHED) {
                vm.state.collect { if (it is MainUiState.PhoneEntry) sawPhoneEntry = true }
            }
            val error = withTimeout(10_000) { vm.state.first { it is MainUiState.Error } } as MainUiState.Error
            assertEquals(expectedError, error.type)
            assertEquals("credential-a", store.saved?.refreshToken)
            assertEquals(1, server.requestCount)
            withContext(main) { vm.retry() }
            withTimeout(10_000) { vm.state.first { it is MainUiState.Ready } }
            assertEquals(3, server.requestCount)
            assertEquals("credential-b", store.saved?.refreshToken)
            assertEquals("/api/v1/auth/refresh", server.takeRequest().path)
            assertEquals("/api/v1/auth/refresh", server.takeRequest().path)
            assertEquals("/api/v1/vpn/access", server.takeRequest().path)
            assertFalse(sawPhoneEntry)
            assertFalse(store.cleared)
        } finally {
            observer?.cancelAndJoin()
            withContext(main) { viewModels.clear() }
            Dispatchers.resetMain()
            main.close()
            server.shutdown()
        }
    }

    @Test fun persistedRotationSurvivesRepositoryRecreationOverHttp() = runBlocking {
        val server = MockWebServer()
        server.enqueue(success())
        server.enqueue(success().setBody(fixture().replace("access-b", "access-c").replace("credential-b", "credential-c")))
        server.start()
        try {
            val api = ApiFactory.create(server.url("/").toString())
            val store = WireStore()
            PhoneAuthRepository(api, store).restoreSession()
            store.saved = store.saved!!.copy(expiresAtMillis = 0)
            val recreated = PhoneAuthRepository(api, store).restoreSession()
            assertEquals("credential-c", recreated?.refreshToken)
            assertEquals("existing-account", recreated?.accountId)
            assertTrue(server.takeRequest().body.readUtf8().contains("credential-a"))
            assertTrue(server.takeRequest().body.readUtf8().contains("credential-b"))
            assertFalse(store.cleared)
        } finally { server.shutdown() }
    }

    private fun fixture() = javaClass.getResourceAsStream("/refresh-success.json")!!.bufferedReader().use { it.readText() }
    private fun success() = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(fixture())

    private class WireStore : SessionStore {
        var saved: Session? = Session("expired-access", "credential-a", 0, "existing-account", expiresAtMillis = 0)
        var cleared = false
        override suspend fun session() = saved
        override suspend fun save(session: Session) { saved = session }
        override suspend fun clear(reason: SessionClearReason) { cleared = true; saved = null }
    }
}
