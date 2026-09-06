package com.myvpn.android.data

import kotlinx.serialization.Serializable

@Serializable data class DeviceRegisterRequest(val installId: String, val deviceSecret: String)
@Serializable data class AuthResponse(val accountId: String, val accessToken: String, val refreshToken: String, val tokenType: String = "Bearer", val expiresIn: Long)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class PhoneVerificationStartRequest(val phone: String)
@Serializable data class PhoneVerificationStartResponse(val verificationId: String, val callPhone: String, val callPhonePretty: String? = null, val expiresAt: String)
@Serializable data class PhoneVerificationStatusResponse(val status: String, val exchangeToken: String? = null)
@Serializable data class PhoneVerificationExchangeRequest(val verificationId: String, val exchangeToken: String)
@Serializable data class PhoneAuthResponse(val accountId: String, val accessToken: String, val refreshToken: String, val tokenType: String = "Bearer", val expiresIn: Long, val accessStatus: String, val accessExpiresAt: String? = null)
@Serializable data class VpnQuota(val limitBytes: Long, val periodStartedAt: String? = null, val periodEndsAt: String? = null)
@Serializable data class VpnAccessResponse(val status: String, val entitlement: String, val configuration: String? = null, val quota: VpnQuota? = null, val premiumExpiresAt: String? = null)
@Serializable data class AppVersionResponse(val latestVersionCode: Long, val latestVersionName: String, val minimumSupportedVersionCode: Long, val apkUrl: String, val changelog: String)

data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val accountId: String? = null,
    val accessStatus: String? = null,
    val accessExpiresAt: String? = null,
    val expiresAtMillis: Long = System.currentTimeMillis() + expiresIn * 1000
) {
    fun accessTokenIsValid(nowMillis: Long = System.currentTimeMillis()) = expiresAtMillis > nowMillis + 5_000
}

class SessionExpiredException : IllegalStateException()
