package com.myvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnServiceSessionTest {
    @Test fun connectKeepsTunOpenUntilNativeStopAndRejectsDuplicate() {
        val calls = mutableListOf<String>(); val bridge = Bridge(calls); val tun = Handle(calls)
        val session = VpnServiceSession { LibXrayCoreEngine(bridge) }
        assertTrue(session.connect(CONFIG, { calls += "tun"; tun }) { true })
        assertFalse(session.connect(CONFIG, { error("duplicate establish") }) { true })
        assertEquals(listOf("tun", "protect", "dns", "fd:42", "run", "state"), calls)
        session.disconnect(); session.disconnect()
        assertEquals(listOf("tun", "protect", "dns", "fd:42", "run", "state", "stop", "reset", "close"), calls)
        assertFalse(session.active)
    }

    @Test fun partialStartupFailureStopsNativeAndClosesTun() {
        val calls = mutableListOf<String>(); val bridge = Bridge(calls, failRun = true); val tun = Handle(calls)
        val session = VpnServiceSession { LibXrayCoreEngine(bridge) }
        assertFalse(session.connect(CONFIG, { tun }) { true })
        assertEquals(listOf("protect", "dns", "fd:42", "run", "stop", "reset", "close"), calls)
        assertFalse(session.active)
    }

    private class Handle(private val calls: MutableList<String>) : TunHandle { override val fd = 42; override fun close() { calls += "close" } }
    private class Bridge(private val calls: MutableList<String>, private val failRun: Boolean = false) : LibXrayBridge {
        override fun setTunFd(fd: Int) { calls += "fd:$fd" }
        override fun registerSocketProtection(protect: (Long) -> Boolean) { calls += "protect" }
        override fun initDns(protect: (Long) -> Boolean, server: String) { calls += "dns" }
        override fun runXrayFromJson(config: String) { calls += "run"; if (failRun) error("failed") }
        override fun stopXray() { calls += "stop" }
        override fun isRunning(): Boolean { calls += "state"; return true }
        override fun resetDns() { calls += "reset" }
    }
    private companion object { const val CONFIG = "vless://123e4567-e89b-12d3-a456-426614174000@vpn.example.test:443?type=tcp&security=reality&encryption=none&sni=s&fp=chrome&pbk=p&sid=a" }
}
