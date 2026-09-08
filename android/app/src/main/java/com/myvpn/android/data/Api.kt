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

    override suspend fun restoreSession(): Session? = mutex.withLock {
        val saved = loadPersistedSession() ?: run {
            currentSession = null
            log("Auth restore: refreshPresent=false result=MISSING_OR_CORRUPTED_CREDENTIAL")
            return@withLock null
        }
        log("AUTH_STARTUP ${if (saved.accessTokenIsValid()) "ACCESS_VALID" else "ACCESS_EXPIRED"} accessPresent=${saved.accessToken.isNotBlank()}")
        if (saved.accessTokenIsValid()) saved.also { currentSession = it } else refresh(saved)
    }

    override suspend fun startVerification(phone: String) = api.startPhone(PhoneVerificationStartRequest(phone))
    override suspend fun verificationStatus(verificationId: String) = api.phoneStatus(verificationId)
    override suspend fun exchange(verificationId: String, exchangeToken: String): Session = mutex.withLock {
        val response = api.exchangePhone(PhoneVerificationExchangeRequest(verificationId, exchangeToken))
        Session(response.accessToken, response.refreshToken, response.expiresIn, response.accountId, response.accessStatus, response.accessExpiresAt).also { session ->
            store.save(session)
            currentSession = session
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

    private suspend fun refresh(previous: Session, reconcile: Boolean = true): Session? = try {
        log("REFRESH_ATTEMPT")
        val response = api.refresh(RefreshRequest(previous.refreshToken))
        if (response.accessToken.isBlank() || response.refreshToken.isBlank()) {
            throw SessionRefreshUnavailableException(IllegalStateException("Refresh response has missing credentials"))
        }
        Session(response.accessToken, response.refreshToken, response.expiresIn, previous.accountId, previous.accessStatus, previous.accessExpiresAt).also { session ->
            store.save(session)
            currentSession = session
            log("REFRESH_SUCCESS")
        }
    } catch (exception: HttpException) {
        val code = refreshErrorCode(exception)
        if (code == "SESSION_REVOKED") {
            log("SESSION_REVOKED")
            currentSession = null
            store.clear(SessionClearReason.REVOKED_SESSION)
            null
        } else if (code == "REFRESH_TOKEN_INVALID" || code == "REFRESH_TOKEN_REVOKED") {
            // Reconcile with durable storage before retrying. Never discard an
            // unrecognised credential: only the session endpoint can prove revoke.
            log("REFRESH_INVALID")
            val persisted = loadPersistedSession()
            if (reconcile && persisted != null && persisted.refreshToken != previous.refreshToken) {
                currentSession = persisted
                if (persisted.accessTokenIsValid()) persisted else refresh(persisted, reconcile = false)
            } else throw SessionRecoveryException(exception)
        } else {
            log("REFRESH_TRANSIENT_ERROR status=${exception.code()}")
            throw SessionRefreshUnavailableException(exception)
        }
    } catch (exception: java.io.IOException) {
        log("REFRESH_TRANSIENT_ERROR transport=${exception::class.java.simpleName}")
        throw SessionRefreshUnavailableException(exception)
    }

    private suspend fun loadPersistedSession(): Session? = try {
        store.session()?.also {
            if (it.refreshToken.isBlank()) throw CorruptedLocalCredentialException(IllegalStateException("Empty local credential"))
        }
    } catch (failure: CorruptedLocalCredentialException) {
        currentSession = null
        store.clear(SessionClearReason.CORRUPTED_LOCAL_CREDENTIAL)
        null
    }

    private fun refreshErrorCode(exception: HttpException): String? {
        if (exception.code() != 401 && exception.code() != 403) return null
        return runCatching {
            authErrorJson.decodeFromString<AuthErrorResponse>(
                exception.response()?.errorBody()?.string().orEmpty())
        }.getOrNull()?.code
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
            if (exception.code() != 401 && exception.code() != 403) throw exception
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
