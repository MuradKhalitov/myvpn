package com.myvpn.android.vpn

import org.junit.Assert.*
import org.junit.Test

class VpnForegroundOwnershipTest {
    @Test fun duplicatePromotionKeepsSingleOwnerAndRemovalIsIdempotent() {
        val foreground = VpnForegroundOwnership()
        val service = Any()
        var promotions = 0
        var removals = 0
        repeat(2) { foreground.promote(service) { promotions++ } }
        repeat(2) { foreground.remove(service) { removals++ } }
        assertEquals(2, promotions) // Android receives the same notification ID each time.
        assertEquals(1, removals)
    }

    @Test fun oldCleanupCannotRemoveReplacementNotification() {
        val foreground = VpnForegroundOwnership()
        val old = Any(); val replacement = Any()
        foreground.promote(old) {}
        foreground.promote(replacement) {}
        foreground.remove(old) { fail("old cleanup removed replacement foreground") }
        var updated = false
        foreground.update(replacement) { updated = true }
        assertTrue(updated)
        var removed = false
        foreground.remove(replacement) { removed = true }
        assertTrue(removed)
    }

    @Test fun oldConnectedEventCannotOverwriteReplacementNotification() {
        val foreground = VpnForegroundOwnership()
        val old = Any(); val replacement = Any()
        foreground.promote(old) {}
        foreground.promote(replacement) {}
        foreground.update(old) { fail("stale notification update") }
    }

    @Test fun failedPromotionDoesNotTakeOwnershipFromActiveService() {
        val foreground = VpnForegroundOwnership()
        val active = Any(); val failed = Any()
        foreground.promote(active) {}
        assertThrows(IllegalStateException::class.java) { foreground.promote(failed) { error("promotion failed") } }
        foreground.remove(failed) { fail("failed instance removed active notification") }
        var updated = false
        foreground.update(active) { updated = true }
        assertTrue(updated)
    }
}
