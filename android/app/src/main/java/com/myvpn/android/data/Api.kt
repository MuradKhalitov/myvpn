package com.myvpn.android.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.myvpn.android.BuildConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

private val authErrorJson = Json { ignoreUnknownKeys = true }

interface MyVpnApi {
    // Kept only for backward compatibility with legacy DEVICE installations.
    @POST("api/v1/device/register") suspend fun register(@Body request: DeviceRegisterRequest): AuthResponse
    @POST("api/v1/auth/refresh") suspend fun refresh(@Body request: RefreshRequest): AuthResponse
    @POST("api/v1/auth/phone/start") suspend fun startPhone(@Body request: PhoneVerificationStartRequest): PhoneVerificationStartResponse
    @GET("api/v1/auth/phone/{verificationId}/status") suspend fun phoneStatus(@Path("verificationId") verificationId: String): PhoneVerificationStatusResponse
    @POST("api/v1/auth/phone/exchange") suspend fun exchangePhone(@Body request: PhoneVerificationExchangeRequest): PhoneAuthResponse
    @GET("api/v1/app/version") suspend fun appVersion(): AppVersionResponse
    @GET("api/v1/vpn/access") suspend fun access(@Header("Authorization") bearer: String): VpnAccessResponse
}

interface PhoneAuthSource {
    suspend fun restoreSession(): Session?
    suspend fun startVerification(phone: String): PhoneVerificationStartResponse
    suspend fun verificationStatus(verificationId: String): PhoneVerificationStatusResponse
    suspend fun exchange(verificationId: String, exchangeToken: String): Session
}

class PhoneAuthRepository(private val api: MyVpnApi, private val store: SessionStore) : PhoneAuthSource {
    private val mutex = Mutex()
    @Volatile private var currentSession: Session? = null

    override suspend fun restoreSession(): Session? {
        val saved = loadPersistedSession() ?: run {
            log("Auth restore: refreshPresent=false result=MISSING_OR_CORRUPTED_CREDENTIAL")
            return null
        }
        log("Auth restore: accessPresent=${saved.accessToken.isNotBlank()} accessExpired=${!saved.accessTokenIsValid()} refreshPresent=true")
        if (saved.accessTokenIsValid()) return saved.also { currentSession = it }
        return mutex.withLock {
            val latest = currentSession?.takeIf { it.accessTokenIsValid() }
                ?: loadPersistedSession()?.takeIf { it.accessTokenIsValid() }
            latest?.also { currentSession = it } ?: refresh(loadPersistedSession() ?: return@withLock null)
        }
    }

    override suspend fun startVerification(phone: String) = api.startPhone(PhoneVerificationStartRequest(phone))
    override suspend fun verificationStatus(verificationId: String) = api.phoneStatus(verificationId)
    override suspend fun exchange(verificationId: String, exchangeToken: String): Session {
        val response = api.exchangePhone(PhoneVerificationExchangeRequest(verificationId, exchangeToken))
        return Session(response.accessToken, response.refreshToken, response.expiresIn, response.accountId, response.accessStatus, response.accessExpiresAt).also { session ->
            currentSession = session
            store.save(session)
        }
    }

    suspend fun accessToken(): String {
        val active = currentSession?.takeIf { it.accessTokenIsValid() } ?: restoreSession()
        return active?.accessToken?.takeIf(String::isNotBlank) ?: throw SessionExpiredException()
    }
    /**
     * A batch of requests can observe the same expired access token.  Only the
     * first holder rotates its refresh credential; waiters reuse that result.
     */
    suspend fun refreshAfterUnauthorized(failedAccessToken: String): String = mutex.withLock {
        val saved = currentSession ?: loadPersistedSession() ?: throw SessionExpiredException()
        if (saved.accessToken != failedAccessToken && saved.accessTokenIsValid()) {
            return@withLock saved.accessToken
        }
        (refresh(saved) ?: throw SessionExpiredException()).accessToken
    }

    private suspend fun refresh(previous: Session): Session? = try {
        log("Refresh attempted")
        val response = api.refresh(RefreshRequest(previous.refreshToken))
        if (response.accessToken.isBlank() || response.refreshToken.isBlank()) {
            throw SessionRefreshUnavailableException(IllegalStateException("Refresh response has missing credentials"))
        }
        Session(response.accessToken, response.refreshToken, response.expiresIn, previous.accountId, previous.accessStatus, previous.accessExpiresAt).also { session ->
            currentSession = session
            store.save(session)
            log("Refresh result=SUCCESS")
        }
    } catch (exception: HttpException) {
        val reason = confirmedInvalidReason(exception)
        if (reason != null) {
            log("Refresh result=$reason")
            currentSession = null
            store.clear(reason)
            null
        } else {
            log("Refresh result=TRANSIENT_HTTP_${exception.code()}")
            throw SessionRefreshUnavailableException(exception)
        }
    } catch (exception: java.io.IOException) {
        log("Refresh result=TRANSPORT_${exception::class.java.simpleName}")
        throw SessionRefreshUnavailableException(exception)
    }

    private suspend fun loadPersistedSession(): Session? = try {
        store.session()
    } catch (failure: CorruptedLocalCredentialException) {
        currentSession = null
        store.clear(SessionClearReason.CORRUPTED_LOCAL_CREDENTIAL)
        null
    }

    private fun confirmedInvalidReason(exception: HttpException): SessionClearReason? {
        if (exception.code() != 401 && exception.code() != 403) return null
        val code = runCatching {
            authErrorJson.decodeFromString<AuthErrorResponse>(
                exception.response()?.errorBody()?.string().orEmpty())
        }.getOrNull()?.code
        return when (code) {
            "REFRESH_TOKEN_INVALID" -> SessionClearReason.INVALID_REFRESH
            "REFRESH_TOKEN_REVOKED", "SESSION_REVOKED" -> SessionClearReason.REVOKED_SESSION
            else -> null
        }
    }

    private fun log(message: String) {
        runCatching { android.util.Log.i("MyVpnAuth", message) }
    }
}

interface VpnAccessSource { suspend fun current(): VpnAccessResponse }

interface AppVersionSource { suspend fun current(): AppVersionResponse }

class AppVersionRepository(private val api: MyVpnApi) : AppVersionSource {
    override suspend fun current(): AppVersionResponse = api.appVersion()
}

class VpnAccessRepository(private val api: MyVpnApi, private val auth: PhoneAuthRepository) : VpnAccessSource {
    override suspend fun current(): VpnAccessResponse {
        val token = auth.accessToken()
        return try {
            api.access("Bearer $token")
        } catch (exception: HttpException) {
            if (exception.code() != 401) throw exception
            api.access("Bearer ${auth.refreshAfterUnauthorized(token)}")
        }
    }
}

object ApiFactory {
    fun create(): MyVpnApi {
        val json = Json { ignoreUnknownKeys = true }
        return Retrofit.Builder().baseUrl(BuildConfig.API_BASE_URL).client(OkHttpClient.Builder().build()).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(MyVpnApi::class.java)
    }
}
