package com.myvpn.android.data

import org.junit.Assert.*
import org.junit.Test

class LaunchableAppsRepositoryTest {
    private val apps = listOf(InstalledApp("z", "Zoo"), InstalledApp("a", "alpha"), InstalledApp("a", "alpha"), InstalledApp("self", "MyVPN"))
    @Test fun duplicatePackagesRemoved() { assertEquals(3, normalizeLaunchableApps(apps, "other").size) }
    @Test fun sortedByLabelIgnoringCase() { assertEquals(listOf("a", "self", "z"), normalizeLaunchableApps(apps, "other").map { it.packageName }) }
    @Test fun ownPackageExcluded() { assertFalse(normalizeLaunchableApps(apps, "self").any { it.packageName == "self" }) }
    @Test fun searchUsesLabelIgnoringCase() { assertEquals(listOf(apps[0]), filterLaunchableApps(apps, " ZOO ")) }
}
