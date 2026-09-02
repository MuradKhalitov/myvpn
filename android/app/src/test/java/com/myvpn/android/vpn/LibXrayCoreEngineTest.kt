package com.myvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class LibXrayCoreEngineTest {
    @Test fun startOrdersTunBeforeRunAndStopsOnce() {
        val fake=FakeBridge(); val engine=LibXrayCoreEngine(fake)
        engine.start(CONFIG, 42) { it == 7L }
        engine.start(CONFIG, 42) { true }
        assertEquals(listOf("protect","dns","tun:42","run","state"),fake.calls)
        engine.stop();engine.stop()
        assertEquals(1,fake.calls.count{it=="stop"});assertEquals(1,fake.calls.count{it=="reset"});assertFalse(engine.running)
    }
    @Test fun failedRunCleansUpAndDoesNotExposeSensitiveConfiguration() {
        val fake=FakeBridge(failRun=true);val error=assertThrows(LibXrayNativeException::class.java){LibXrayCoreEngine(fake).start(CONFIG,42){true}}
        assertEquals(listOf("protect","dns","tun:42","run","stop","reset"),fake.calls);assertFalse(error.message!!.contains("123e4567"))
    }
    private class FakeBridge(private val failRun:Boolean=false):LibXrayBridge { val calls=mutableListOf<String>();override fun setTunFd(fd:Int){calls+="tun:$fd"};override fun registerSocketProtection(protect:(Long)->Boolean){calls+="protect";protect(7)};override fun initDns(protect:(Long)->Boolean,server:String){calls+="dns"};override fun runXrayFromJson(config:String){calls+="run";if(failRun)throw IllegalStateException()};override fun stopXray(){calls+="stop"};override fun isRunning():Boolean{calls+="state";return true};override fun resetDns(){calls+="reset"} }
    companion object { private const val CONFIG="vless://123e4567-e89b-12d3-a456-426614174000@vpn.example.test:443?type=tcp&security=reality&encryption=none&sni=s&fp=chrome&pbk=p&sid=a" }
}
