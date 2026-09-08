package com.myvpn.android.data

import kotlinx.serialization.Serializable

@Serializable data class DeviceRegisterRequest(val installId: String, val deviceSecret: String)
@Serializable data class AuthResponse(val accountId: String, val accessToken: String, val refreshToken: String, val tokenType: String = "Bearer", val expiresIn: Long)
/** /auth/refresh returns AuthTokens, without accountId (unlike registration/exchange). */
@Serializable data class RefreshResponse(val accessToken: String, val refreshToken: String, val tokenType: String = "Bearer", val expiresIn: Long)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class PhoneVerificationStartRequest(val phone: String)
@Serializable data class PhoneVerificationStartResponse(val verificationId: String, val callPhone: String, val callPhonePretty: String? = null, val expiresAt: String)
@Serializable data class PhoneVerificationStatusResponse(val status: String, val exchangeToken: String? = null)
@Serializable data class PhoneVerificationExchangeRequest(val verificationId: String, val exchangeToken: String)
@Serializable data class PhoneAuthResponse(val accountId: String, val accessToken: String, val refreshToken: String, val tokenType: String = "Bearer", val expiresIn: Long, val accessStatus: String, val accessExpiresAt: String? = null)
@Serializable data class VpnQuota(val limitBytes: Long, val periodStartedAt: String? = null, val periodEndsAt: String? = null)
@Serializable data class VpnAccessResponse(val status: String, val entitlement: String, val configuration: String? = null, val quota: VpnQuota? = null, val premiumExpiresAt: String? = null)
@Serializable data class AppVersionResponse(val latestVersionCode: Long, val latestVersionName: String, val minimumSupportedVersionCode: Long, val apkUrl: String, val changelog: String)
@Serializable data class AuthErrorResponse(val code: String? = null, val message: String? = null)

data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val accountId: String? = null,
    val accessStatus: String? = null,
    val accessExpiresAt: String? = null,
    val expiresAtMillis: Long = System.currentTimeMillis() + expiresIn * 1000
) {
    fun accessTokenIsValid(nowMillis: Long = System.currentTimeMillis()) = accessToken.isNotBlank() && expiresAtMillis > nowMillis + 5_000
}

class SessionExpiredException : IllegalStateException()
class CorruptedLocalCredentialException(cause: Throwable) : IllegalStateException(cause)

enum class SessionClearReason { REVOKED_SESSION, CORRUPTED_LOCAL_CREDENTIAL }

/** Invalid credentials do not prove that the device session was revoked. */
class SessionRecoveryException(cause: Throwable) : IllegalStateException(cause)

/** A transient refresh failure. The encrypted session must be retained for retry. */
class SessionRefreshUnavailableException(cause: Throwable) : IllegalStateException(cause)
