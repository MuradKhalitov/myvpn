package com.myvpn.android.data

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
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

    @Test fun revokedRefreshClearsSessionAndReturnsNoSession() = runTest {
        val store = FakeStore(expiredSession())
        val api = FakeApi(refreshFailure = unauthorized())

        assertNull(PhoneAuthRepository(api, store).restoreSession())
        assertTrue(store.cleared)
    }

    @Test fun transientRefreshFailureKeepsEncryptedSessionForRetry() = runTest {
        val store = FakeStore(expiredSession())
        val api = FakeApi(refreshFailure = java.io.IOException("offline"))

        val failure = runCatching { PhoneAuthRepository(api, store).restoreSession() }.exceptionOrNull()

        assertTrue(failure is SessionRefreshUnavailableException)
        assertSame(store.initial, store.value)
        assertTrue(!store.cleared)
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

    private fun expiredSession() = Session("old-access", "old-refresh", 0, "account", expiresAtMillis = 0)
    private fun validSession(access: String, refresh: String) = Session(access, refresh, 3_600, "account")
    private fun unauthorized() = HttpException(Response.error<AuthResponse>(401, "{}".toResponseBody("application/json".toMediaType())))

    private class FakeStore(initialValue: Session?) : SessionStore {
        val initial = initialValue
        var value = initialValue
        var cleared = false
        override suspend fun session() = value
        override suspend fun save(session: Session) { value = session }
        override suspend fun clear() { cleared = true; value = null }
    }

    private class FakeApi(
        private val refreshFailure: Throwable? = null,
        private val refreshDelayMillis: Long = 0,
        private val rejectAccessToken: String? = null
    ) : MyVpnApi {
        var refreshCalls = 0
        val accessCalls = mutableListOf<String>()
        override suspend fun register(request: DeviceRegisterRequest) = auth()
        override suspend fun refresh(request: RefreshRequest): AuthResponse {
            refreshCalls++
            refreshFailure?.let { throw it }
            if (refreshDelayMillis > 0) delay(refreshDelayMillis)
            return auth()
        }
        override suspend fun startPhone(request: PhoneVerificationStartRequest) = PhoneVerificationStartResponse("verification", "+79990000000", "+7 999 000-00-00", "2026-10-01T00:00:00Z")
        override suspend fun phoneStatus(verificationId: String) = PhoneVerificationStatusResponse("PENDING")
        override suspend fun exchangePhone(request: PhoneVerificationExchangeRequest) = PhoneAuthResponse("account", "new-access", "new-refresh", expiresIn = 900, accessStatus = "TRIAL")
        override suspend fun appVersion() = AppVersionResponse(1, "1.0.0", 1, "https://example.test/app.apk", "")
        override suspend fun access(bearer: String): VpnAccessResponse {
            accessCalls += bearer
            if (bearer == "Bearer $rejectAccessToken") {
                throw HttpException(Response.error<VpnAccessResponse>(401, "{}".toResponseBody("application/json".toMediaType())))
            }
            return VpnAccessResponse("READY", "TRIAL", "config")
        }
        private fun auth() = AuthResponse("account", "new-access", "new-refresh", expiresIn = 900)
    }
}
