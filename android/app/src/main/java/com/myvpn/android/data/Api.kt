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

interface MyVpnApi {
    // Kept only for backward compatibility with legacy DEVICE installations.
    @POST("api/v1/device/register") suspend fun register(@Body request: DeviceRegisterRequest): AuthResponse
    @POST("api/v1/auth/refresh") suspend fun refresh(@Body request: RefreshRequest): AuthResponse
    @POST("api/v1/auth/phone/start") suspend fun startPhone(@Body request: PhoneVerificationStartRequest): PhoneVerificationStartResponse
    @GET("api/v1/auth/phone/{verificationId}/status") suspend fun phoneStatus(@Path("verificationId") verificationId: String): PhoneVerificationStatusResponse
    @POST("api/v1/auth/phone/exchange") suspend fun exchangePhone(@Body request: PhoneVerificationExchangeRequest): PhoneAuthResponse
    @GET("api/v1/vpn/access") suspend fun access(@Header("Authorization") bearer: String): VpnAccessResponse
}

interface PhoneAuthSource {
    suspend fun restoreSession(): Session?
    suspend fun startVerification(phone: String): PhoneVerificationStartResponse
    suspend fun verificationStatus(verificationId: String): PhoneVerificationStatusResponse
    suspend fun exchange(verificationId: String, exchangeToken: String): Session
}

class PhoneAuthRepository(private val api: MyVpnApi, private val store: DeviceIdentityStore) : PhoneAuthSource {
    private val mutex = Mutex()
    @Volatile private var currentSession: Session? = null

    override suspend fun restoreSession(): Session? {
        val saved = store.session() ?: return null
        if (saved.accessTokenIsValid()) return saved.also { currentSession = it }
        return refresh(saved)
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

    suspend fun accessToken(): String = (currentSession ?: restoreSession() ?: throw SessionExpiredException()).accessToken
    suspend fun refreshAfterUnauthorized(): String = mutex.withLock {
        val saved = currentSession ?: store.session() ?: throw SessionExpiredException()
        (refresh(saved) ?: throw SessionExpiredException()).accessToken
    }

    private suspend fun refresh(previous: Session): Session? = runCatching {
        val response = api.refresh(RefreshRequest(previous.refreshToken))
        Session(response.accessToken, response.refreshToken, response.expiresIn, previous.accountId, previous.accessStatus, previous.accessExpiresAt).also { session ->
            currentSession = session
            store.save(session)
        }
    }.getOrNull().also { refreshed ->
        if (refreshed == null) {
            currentSession = null
            store.clear()
        }
    }
}

interface VpnAccessSource { suspend fun current(): VpnAccessResponse }

class VpnAccessRepository(private val api: MyVpnApi, private val auth: PhoneAuthRepository) : VpnAccessSource {
    override suspend fun current(): VpnAccessResponse {
        val token = auth.accessToken()
        return try {
            api.access("Bearer $token")
        } catch (exception: HttpException) {
            if (exception.code() != 401) throw exception
            api.access("Bearer ${auth.refreshAfterUnauthorized()}")
        }
    }
}

object ApiFactory {
    fun create(): MyVpnApi {
        val json = Json { ignoreUnknownKeys = true }
        return Retrofit.Builder().baseUrl(BuildConfig.API_BASE_URL).client(OkHttpClient.Builder().build()).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(MyVpnApi::class.java)
    }
}
