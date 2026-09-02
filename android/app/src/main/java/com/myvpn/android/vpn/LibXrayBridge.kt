package com.myvpn.android.vpn

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import libXray.DialerController
import libXray.LibXray

interface LibXrayBridge {
    fun setTunFd(fd: Int)
    fun registerSocketProtection(protect: (Long) -> Boolean)
    fun initDns(protect: (Long) -> Boolean, server: String)
    fun runXrayFromJson(config: String)
    fun stopXray()
    fun isRunning(): Boolean
    fun resetDns()
}

class LibXrayNativeException : IllegalStateException("VPN engine operation failed")

class GomobileLibXrayBridge : LibXrayBridge {
    private fun controller(protect: (Long) -> Boolean) = DialerController { fd -> protect(fd) }
    override fun setTunFd(fd: Int) = LibXray.setTunFd(fd)
    override fun registerSocketProtection(protect: (Long) -> Boolean) {
        LibXray.registerDialerController(controller(protect))
        LibXray.registerListenerController(controller(protect))
    }
    override fun initDns(protect: (Long) -> Boolean, server: String) = LibXray.initDns(controller(protect), server)
    override fun runXrayFromJson(config: String) {
        val request = LibXray.newXrayRunFromJSONRequest("", "", config)
        verify(LibXray.runXrayFromJSON(request))
    }
    override fun stopXray() = verify(LibXray.stopXray())
    override fun isRunning(): Boolean = LibXray.getXrayState()
    override fun resetDns() = LibXray.resetDns()
    private fun verify(response: String) {
        val decoded = String(Base64.decode(response, Base64.DEFAULT), Charsets.UTF_8)
        val success = Json.parseToJsonElement(decoded).jsonObject["success"]?.jsonPrimitive?.content == "true"
        if (!success) throw LibXrayNativeException()
    }
}
