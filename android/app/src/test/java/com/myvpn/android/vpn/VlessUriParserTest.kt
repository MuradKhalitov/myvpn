package com.myvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class VlessUriParserTest {
    private val parser = VlessUriParser()
    private val valid = "vless://123e4567-e89b-12d3-a456-426614174000@vpn.example.test:443?type=tcp&security=reality&encryption=none&sni=server.example&fp=chrome&pbk=public-key&sid=abcd&flow=xtls-rprx-vision&spx=%2F"

    @Test fun parsesCurrentBackendContract() { val value=parser.parse(valid);assertEquals("vpn.example.test",value.host);assertEquals(443,value.port);assertEquals("xtls-rprx-vision",value.flow) }
    @Test fun rejectsInvalidScheme() = rejects(valid.replace("vless", "vmess"))
    @Test fun rejectsInvalidUuid() = rejects(valid.replace("123e4567-e89b-12d3-a456-426614174000", "invalid"))
    @Test fun rejectsInvalidPort() = rejects(valid.replace(":443?", ":0?"))
    @Test fun rejectsMissingRealityField() = rejects(valid.replace("&pbk=public-key", ""))
    @Test fun rejectsMissingSni() = rejects(valid.replace("&sni=server.example", ""))
    @Test fun rejectsUnsupportedTransport() = rejects(valid.replace("type=tcp", "type=grpc"))
    @Test fun rejectsNonRealitySecurity() = rejects(valid.replace("security=reality", "security=tls"))
    @Test fun rejectsNonNoneEncryption() = rejects(valid.replace("encryption=none", "encryption=aes-128-gcm"))
    private fun rejects(input:String){val error=assertThrows(VlessConfigurationException::class.java){parser.parse(input)};assertFalse(error.message!!.contains("123e4567"));assertFalse(error.message!!.contains("public-key"))}
}
