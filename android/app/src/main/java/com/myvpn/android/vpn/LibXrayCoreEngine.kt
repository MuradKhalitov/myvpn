package com.myvpn.android.vpn

class LibXrayCoreEngine(
    private val bridge: LibXrayBridge,
    private val parser: VlessUriParser = VlessUriParser(),
    private val configFactory: XrayRuntimeConfigFactory = XrayRuntimeConfigFactory()
) {
    var running = false
        private set

    fun start(configuration: String, tunFd: Int, protect: (Long) -> Boolean) {
        if (running) return
        try {
            val parsed = parser.parse(configuration)
            bridge.registerSocketProtection(protect)
            bridge.initDns(protect, DNS_SERVER)
            bridge.setTunFd(tunFd)
            bridge.runXrayFromJson(configFactory.create(parsed))
            if (!bridge.isRunning()) throw LibXrayNativeException()
            running = true
        } catch (_: Exception) {
            cleanup()
            throw LibXrayNativeException()
        }
    }

    fun stop() { if (running) cleanup() }

    private fun cleanup() {
        runCatching { bridge.stopXray() }
        runCatching { bridge.resetDns() }
        running = false
    }

    companion object { const val DNS_SERVER = "1.1.1.1:53" }
}
