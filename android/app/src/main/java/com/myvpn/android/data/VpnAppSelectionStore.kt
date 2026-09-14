package com.myvpn.android.data

import android.content.SharedPreferences
import com.myvpn.android.vpn.VpnAppSelection
import com.myvpn.android.vpn.VpnAppsMode
import java.io.IOException

interface VpnAppSelectionSource {
    fun get(): VpnAppSelection
    fun save(selection: VpnAppSelection)
}

class VpnAppSelectionStore(private val preferences: SharedPreferences) : VpnAppSelectionSource {
    override fun get(): VpnAppSelection {
        // One map snapshot prevents mixing mode and packages from different saves.
        val values = preferences.all
        val mode = when (values[MODE]) {
            null, "ALL" -> VpnAppsMode.ALL
            "SELECTED" -> VpnAppsMode.SELECTED
            else -> throw IOException("Invalid VPN app selection")
        }
        val packages = (values[PACKAGES] as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
        return VpnAppSelection(mode, packages)
    }

    override fun save(selection: VpnAppSelection) {
        require(selection.mode != VpnAppsMode.SELECTED || selection.packageNames.isNotEmpty())
        require(selection.packageNames.none { it.isBlank() })
        if (!preferences.edit().putString(MODE, selection.mode.name)
                .putStringSet(PACKAGES, selection.packageNames.toSet()).commit()) {
            throw IOException("Could not save VPN app selection")
        }
    }

    companion object {
        private const val MODE = "vpn_apps_mode"
        private const val PACKAGES = "vpn_selected_packages"
    }
}
