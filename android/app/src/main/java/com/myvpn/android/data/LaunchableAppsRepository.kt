package com.myvpn.android.data

import android.content.Intent
import android.content.pm.PackageManager

data class InstalledApp(val packageName: String, val label: String)

fun interface LaunchableAppsSource { fun get(): List<InstalledApp> }

class LaunchableAppsRepository(private val manager: PackageManager, private val ownPackage: String) : LaunchableAppsSource {
    override fun get(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = manager.queryIntentActivities(intent, 0).map { resolved ->
            val info = resolved.activityInfo.applicationInfo
            InstalledApp(info.packageName, manager.getApplicationLabel(info).toString())
        }
        return normalizeLaunchableApps(apps, ownPackage)
    }
}

internal fun normalizeLaunchableApps(apps: List<InstalledApp>, ownPackage: String) =
    apps.filter { it.packageName != ownPackage }.distinctBy { it.packageName }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

internal fun filterLaunchableApps(apps: List<InstalledApp>, query: String) =
    apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
