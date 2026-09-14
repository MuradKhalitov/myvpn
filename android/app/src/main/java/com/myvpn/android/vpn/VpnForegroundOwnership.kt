package com.myvpn.android.vpn

/** Short notification operations only; never holds the native lifecycle mutex. */
internal class VpnForegroundOwnership {
    private var owner: Any? = null

    @Synchronized
    fun promote(service: Any, action: () -> Unit) {
        action()
        owner = service
    }

    @Synchronized
    fun update(service: Any, action: () -> Unit) {
        if (owner === service) action()
    }

    @Synchronized
    fun remove(service: Any, action: () -> Unit) {
        if (owner === service) {
            action()
            owner = null
        }
    }
}
