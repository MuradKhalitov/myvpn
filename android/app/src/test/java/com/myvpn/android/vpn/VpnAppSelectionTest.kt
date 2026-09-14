package com.myvpn.android.vpn

import org.junit.Assert.*
import org.junit.Test

class VpnAppSelectionTest {
    @Test fun allNeverAddsPackages() { applyVpnAppSelection(VpnAppSelection(packageNames = setOf("ignored"))) { error("must not add") } }
    @Test fun selectedAddsEachPackage() {
        val calls = mutableListOf<String>()
        applyVpnAppSelection(VpnAppSelection(VpnAppsMode.SELECTED, setOf("a", "b"))) { calls += it; true }
        assertEquals(listOf("a", "b"), calls)
    }
    @Test fun missingPackageDoesNotPreventOthers() {
        val calls = mutableListOf<String>()
        applyVpnAppSelection(VpnAppSelection(VpnAppsMode.SELECTED, setOf("missing", "b"))) { calls += it; it != "missing" }
        assertEquals(listOf("missing", "b"), calls)
    }
    @Test fun allMissingPreventsEstablishAndCoreStart() {
        var establishes = 0
        val session = VpnServiceSession { error("core must not start") }
        val failure = assertThrows(NoSelectedAppsException::class.java) {
            session.connect("unused", {
                applyVpnAppSelection(VpnAppSelection(VpnAppsMode.SELECTED, setOf("missing"))) { false }
                establishes++; null
            }, { true })
        }
        assertEquals(NO_SELECTED_APPS_MESSAGE, failure.message)
        assertEquals(0, establishes)
        assertFalse(session.active)
    }
    @Test fun emptyPersistedSelectedDoesNotFallBackToAll() {
        assertThrows(NoSelectedAppsException::class.java) { applyVpnAppSelection(VpnAppSelection(VpnAppsMode.SELECTED)) { true } }
    }
}
