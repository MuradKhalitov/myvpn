package com.myvpn.android.vpn

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class XrayRuntimeConfigFactory {
    fun create(configuration: VlessRealityConfiguration): String = Json.encodeToString(buildJsonObject {
        putJsonArray("inbounds") {
            add(buildJsonObject {
                put("tag", "tun-in")
                put("protocol", "tun")
                put("port", 0)
                putJsonObject("settings") { put("name", "myvpn0"); put("MTU", 1400) }
            })
        }
        putJsonArray("outbounds") {
            add(buildJsonObject {
                put("tag", "proxy")
                put("protocol", "vless")
                putJsonObject("settings") {
                    putJsonArray("vnext") {
                        add(buildJsonObject {
                            put("address", configuration.host); put("port", configuration.port)
                            putJsonArray("users") {
                                add(buildJsonObject {
                                    put("id", configuration.id.toString()); put("encryption", "none")
                                    configuration.flow?.let { put("flow", it) }
                                })
                            }
                        })
                    }
                }
                putJsonObject("streamSettings") {
                    put("network", "tcp"); put("security", "reality")
                    putJsonObject("realitySettings") {
                        put("show", false); put("serverName", configuration.sni)
                        put("fingerprint", configuration.fingerprint); put("publicKey", configuration.publicKey)
                        put("shortId", configuration.shortId); configuration.spiderX?.let { put("spiderX", it) }
                    }
                }
            })
            add(buildJsonObject { put("tag", "direct"); put("protocol", "freedom") })
        }
    })
}
