package com.myvpn.android.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.UUID

class XrayRuntimeConfigFactoryTest {
    @Test fun createsRealityOutboundWithoutLeakingModelToString() {
        val value=VlessRealityConfiguration(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),"vpn.example.test",443,"server.example","chrome","public-key","abcd","xtls-rprx-vision","/")
        val root=Json.parseToJsonElement(XrayRuntimeConfigFactory().create(value)).jsonObject
        assertEquals("tun",root["inbounds"]!!.jsonArray[0].jsonObject["protocol"]!!.jsonPrimitive.content)
        assertEquals("vless",root["outbounds"]!!.jsonArray[0].jsonObject["protocol"]!!.jsonPrimitive.content)
        assertFalse(value.toString().contains("public-key"))
    }
}
