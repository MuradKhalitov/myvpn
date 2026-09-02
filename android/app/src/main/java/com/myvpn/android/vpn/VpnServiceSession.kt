package com.myvpn.android.vpn

/** JVM-testable ownership boundary for the TUN descriptor and native core. */
interface TunHandle {
    val fd: Int
    fun close()
}

class VpnServiceSession(
    private val createCore: () -> LibXrayCoreEngine
) {
    private var tun: TunHandle? = null
    private var core: LibXrayCoreEngine? = null

    val active: Boolean get() = tun != null

    fun connect(configuration: String, establishTun: () -> TunHandle?, protect: (Long) -> Boolean): Boolean {
        if (active) return false
        val descriptor = establishTun() ?: return false
        tun = descriptor
        return try {
            createCore().also { it.start(configuration, descriptor.fd, protect); core = it }
            true
        } catch (_: Exception) {
            cleanup()
            false
        }
    }

    fun disconnect() = cleanup()

    fun cleanup() {
        runCatching { core?.stop() }
        core = null
        runCatching { tun?.close() }
        tun = null
    }
}
