package com.myvpn.android.data

import android.content.SharedPreferences
import com.myvpn.android.vpn.*
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class VpnAppSelectionStoreTest {
    @Test fun defaultIsAll() { assertEquals(VpnAppSelection(), VpnAppSelectionStore(PreferencesFake().preferences).get()) }
    @Test fun allRoundTrip() { roundTrip(VpnAppSelection()) }
    @Test fun selectedRoundTripAfterStoreRecreation() { roundTrip(VpnAppSelection(VpnAppsMode.SELECTED, setOf("chrome", "telegram"))) }
    @Test fun duplicatesAreRemoved() {
        val prefs = PreferencesFake()
        VpnAppSelectionStore(prefs.preferences).save(VpnAppSelection(VpnAppsMode.SELECTED, listOf("chrome", "chrome").toSet()))
        assertEquals(setOf("chrome"), VpnAppSelectionStore(prefs.preferences).get().packageNames)
        assertEquals(1, prefs.commits)
    }
    @Test fun emptySelectedRejectedWithoutChangingPreviousSave() {
        val prefs = PreferencesFake(); val store = VpnAppSelectionStore(prefs.preferences)
        assertThrows(IllegalArgumentException::class.java) { store.save(VpnAppSelection(VpnAppsMode.SELECTED)) }
        assertEquals(0, prefs.commits)
        assertEquals(VpnAppSelection(), store.get())
    }
    @Test fun readUsesOneSnapshot() {
        val prefs = PreferencesFake(); VpnAppSelectionStore(prefs.preferences).get()
        assertEquals(1, prefs.reads)
    }
    private fun roundTrip(value: VpnAppSelection) {
        val prefs = PreferencesFake()
        VpnAppSelectionStore(prefs.preferences).save(value)
        assertEquals(value, VpnAppSelectionStore(prefs.preferences).get())
        assertEquals(1, prefs.commits)
    }

    // Exercise the real store against a JVM implementation of Android's interface.
    private class PreferencesFake {
        val values = mutableMapOf<String, Any?>()
        var commits = 0; var reads = 0
        val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "getAll" -> { reads++; values.toMap() }
                "edit" -> editor()
                else -> error(method.name)
            }
        } as SharedPreferences
        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString", "putStringSet" -> { pending[args!![0] as String] = args[1]; proxy }
                    "commit" -> { values.putAll(pending); commits++; true }
                    else -> error(method.name)
                }
            } as SharedPreferences.Editor
        }
    }
}
