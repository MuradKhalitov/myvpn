package com.myvpn.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSharingTest {
    @Test fun shareTextUsesStableLatestApkUrl() {
        val text = AppSharing.shareText()

        assertTrue(text.contains(AppLinks.LATEST_APK_URL))
        assertFalse(text.contains("myvpn-1.0.0.apk"))
    }
}
