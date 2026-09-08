package com.myvpn.android.data

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/** Log only fixed event names, categories, HTTP numbers and presence flags; never payloads. */
internal object AuthDiagnostics {
    fun event(name: String, details: String = "") {
        runCatching { android.util.Log.i("MyVpnAuth", "$name $details") }
    }

    fun category(failure: Throwable): String = when (failure) {
        is SessionRecoveryException -> "INVALID_DEVICE_CREDENTIAL"
        is SessionRefreshUnavailableException -> failure.cause?.let(::category) ?: "REFRESH_UNAVAILABLE"
        is SerializationException -> "RESPONSE_FORMAT"
        is SocketTimeoutException -> "TIMEOUT"
        is UnknownHostException -> "DNS"
        is IOException -> "NETWORK_OR_STORAGE_IO"
        is HttpException -> when (failure.code()) {
            408 -> "TIMEOUT"
            429 -> "RATE_LIMIT"
            in 500..599 -> "BACKEND_5XX"
            else -> "HTTP_ERROR"
        }
        else -> "CLIENT_OR_STORAGE_ERROR"
    }
}
