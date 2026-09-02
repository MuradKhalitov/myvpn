package com.myvpn.android.data
import kotlinx.serialization.Serializable
@Serializable data class DeviceRegisterRequest(val installId:String,val deviceSecret:String)
@Serializable data class AuthResponse(val accountId:String,val accessToken:String,val refreshToken:String,val tokenType:String="Bearer",val expiresIn:Long)
@Serializable data class RefreshRequest(val refreshToken:String)
@Serializable data class VpnQuota(val limitBytes:Long,val periodStartedAt:String?=null,val periodEndsAt:String?=null)
@Serializable data class VpnAccessResponse(val status:String,val entitlement:String,val configuration:String?=null,val quota:VpnQuota?=null,val premiumExpiresAt:String?=null)
data class Session(val accessToken:String,val refreshToken:String,val expiresIn:Long)
