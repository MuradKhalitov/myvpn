package com.myvpn.android.vpn

import java.net.URI
import java.net.URLDecoder
import java.util.UUID

data class VlessRealityConfiguration(
    val id: UUID,
    val host: String,
    val port: Int,
    val sni: String,
    val fingerprint: String,
    val publicKey: String,
    val shortId: String,
    val flow: String?,
    val spiderX: String?
) { override fun toString() = "VlessRealityConfiguration(redacted)" }

class VlessConfigurationException : IllegalArgumentException("Invalid VPN configuration")

class VlessUriParser {
    fun parse(value: String): VlessRealityConfiguration = try {
        val uri = URI(value)
        require(uri.scheme.equals("vless", ignoreCase = true))
        val id = UUID.fromString(uri.rawUserInfo ?: "")
        val host = uri.host?.takeIf { it.isNotBlank() } ?: throw VlessConfigurationException()
        val port = uri.port.takeIf { it in 1..65535 } ?: throw VlessConfigurationException()
        val parameters = parseQuery(uri.rawQuery)
        require(parameters["type"] == "tcp")
        require(parameters["security"] == "reality")
        require(parameters["encryption"] == "none")
        VlessRealityConfiguration(
            id, host, port,
            parameters.required("sni"), parameters.required("fp"),
            parameters.required("pbk"), parameters.required("sid"),
            parameters["flow"]?.takeIf { it.isNotBlank() },
            parameters["spx"]?.takeIf { it.isNotBlank() }
        )
    } catch (_: VlessConfigurationException) {
        throw VlessConfigurationException()
    } catch (_: Exception) {
        throw VlessConfigurationException()
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> = rawQuery
        ?.split('&')
        ?.filter { it.isNotEmpty() }
        ?.associate { part ->
            val index = part.indexOf('=')
            if (index <= 0) throw VlessConfigurationException()
            decode(part.substring(0, index)) to decode(part.substring(index + 1))
        } ?: emptyMap()

    private fun Map<String, String>.required(name: String): String =
        get(name)?.takeIf { it.isNotBlank() } ?: throw VlessConfigurationException()

    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
}
