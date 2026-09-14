package com.myvpn.android.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.sync.Mutex

class MyVpnService : VpnService() {
    private var selectionError: String? = null
    private val lifecycle = VpnServiceLifecycle(
        session = VpnServiceSession { LibXrayCoreEngine(GomobileLibXrayBridge()) },
        mutex = mutex,
        establishTun = ::establishTun,
        protect = { fd -> protect(fd.toInt()) },
        promoteForeground = ::ensureForeground,
        removeForeground = ::removeOwnedForeground,
        stopService = { startId -> stopSelfResult(startId) },
        updateState = { state ->
            diagnostic("STATE ${state::class.java.simpleName}")
            if (state is VpnConnectionState.Connecting) selectionError = null
            VpnConnectionStore.update(if (state is VpnConnectionState.Failed && selectionError != null)
                VpnConnectionState.Failed(selectionError!!) else state)
            if (state is VpnConnectionState.Connected) foregroundOwnership.update(this) {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIFICATION_ID, notification("VPN connected"))
            }
        }
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        diagnostic("COMMAND ${when (intent?.action) { ACTION_CONNECT -> "CONNECT"; ACTION_DISCONNECT -> "DISCONNECT"; else -> "OTHER" }} id=$startId")
        when (intent?.action) {
            // connect promotes synchronously before it submits any coroutine/native work.
            ACTION_CONNECT -> diagnostic("CONNECT_SUBMITTED accepted=${lifecycle.connect(intent.getStringExtra(EXTRA_CONFIGURATION).orEmpty(), startId) != null}")
            ACTION_DISCONNECT -> diagnostic("DISCONNECT_SUBMITTED accepted=${lifecycle.disconnect(startId) != null}")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { diagnostic("DESTROY"); lifecycle.destroy(); super.onDestroy() }

    override fun onRevoke() {
        diagnostic("REVOKE")
        lifecycle.destroy()
        super.onRevoke()
    }

    private fun establishTun(): TunHandle? {
        diagnostic("TUN_BEGIN")
        selectionError = null
        val builder = Builder().setSession("MyVPN").setMtu(1400)
            .addAddress("10.8.0.2", 32).addRoute("0.0.0.0", 0)
            .addAddress("fd00:8::2", 128).addRoute("::", 0)
            .addDnsServer("1.1.1.1")
        val selection = (application as com.myvpn.android.MyVpnApplication).vpnAppSelectionStore.get()
        try {
            applyVpnAppSelection(selection) { packageName ->
                try {
                    builder.addAllowedApplication(packageName)
                    true
                } catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
            }
        } catch (failure: NoSelectedAppsException) {
            selectionError = NO_SELECTED_APPS_MESSAGE
            throw failure
        }
        return builder.establish()?.let(::ParcelTunHandle).also { diagnostic("TUN_END established=${it != null}") }
    }

    private fun ensureForeground() = foregroundOwnership.promote(this) {
        diagnostic("FOREGROUND_BEGIN")
        // Reusing the ID updates one notification, including repeated CONNECT commands.
        startForeground(NOTIFICATION_ID, notification(if (VpnConnectionStore.state.value is VpnConnectionState.Connected)
            "VPN connected" else "Подключение VPN"))
        diagnostic("FOREGROUND_END")
    }

    private fun removeOwnedForeground() = foregroundOwnership.remove(this) {
        diagnostic("FOREGROUND_REMOVE")
        // Old service cleanup may finish after a replacement has already promoted.
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("MyVPN")
        .setContentText(text).setOngoing(true)
        .addAction(0, "Disconnect", PendingIntent.getService(this, 0, disconnectIntent(this), PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun diagnostic(event: String) {
        android.util.Log.i("MyVpnService", "instance=${System.identityHashCode(this)} $event")
    }

    override fun onCreate() { super.onCreate(); (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL_ID, "MyVPN VPN", NotificationManager.IMPORTANCE_LOW)) }

    companion object {
        // libXray is process-wide: old-service cleanup must finish before a new start.
        private val mutex = Mutex()
        private val foregroundOwnership = VpnForegroundOwnership()
        private const val ACTION_CONNECT = "com.myvpn.android.vpn.CONNECT"
        private const val ACTION_DISCONNECT = "com.myvpn.android.vpn.DISCONNECT"
        private const val EXTRA_CONFIGURATION = "com.myvpn.android.vpn.CONFIGURATION"
        private const val CHANNEL_ID = "myvpn_vpn"
        private const val NOTIFICATION_ID = 1
        fun connectIntent(context: Context, configuration: String) = Intent(context, MyVpnService::class.java).setAction(ACTION_CONNECT).putExtra(EXTRA_CONFIGURATION, configuration)
        fun disconnectIntent(context: Context) = Intent(context, MyVpnService::class.java).setAction(ACTION_DISCONNECT)
    }
}

private class ParcelTunHandle(private val descriptor: ParcelFileDescriptor) : TunHandle {
    override val fd: Int get() = descriptor.fd
    override fun close() = descriptor.close()
}
