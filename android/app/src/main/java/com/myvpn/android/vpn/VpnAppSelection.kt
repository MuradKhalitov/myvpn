package com.myvpn.android.vpn

enum class VpnAppsMode { ALL, SELECTED }

data class VpnAppSelection(
    val mode: VpnAppsMode = VpnAppsMode.ALL,
    val packageNames: Set<String> = emptySet()
)

const val NO_SELECTED_APPS_MESSAGE = "Выбранные приложения больше не установлены. Обновите список приложений."

/** addPackage returns false only when the package no longer exists. */
internal fun applyVpnAppSelection(selection: VpnAppSelection, addPackage: (String) -> Boolean) {
    if (selection.mode == VpnAppsMode.ALL) return
    var added = 0
    selection.packageNames.forEach { if (addPackage(it)) added++ }
    if (added == 0) throw NoSelectedAppsException()
}

internal class NoSelectedAppsException : IllegalStateException(NO_SELECTED_APPS_MESSAGE)
