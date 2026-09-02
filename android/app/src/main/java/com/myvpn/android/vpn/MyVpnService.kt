package com.myvpn.android.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MyVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var session = VpnServiceSession { LibXrayCoreEngine(GomobileLibXrayBridge()) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> intent.getStringExtra(EXTRA_CONFIGURATION)?.let { connect(it) }
            ACTION_DISCONNECT -> disconnect()
        }
        return START_NOT_STICKY
    }

    private fun connect(configuration: String) = scope.launch {
        mutex.withLock {
            if (VpnConnectionStore.state.value is VpnConnectionState.Connecting || session.active) return@withLock
            VpnConnectionStore.update(VpnConnectionState.Connecting)
            startForeground(NOTIFICATION_ID, notification())
            try {
                check(session.connect(configuration, ::establishTun) { fd -> protect(fd.toInt()) })
                VpnConnectionStore.update(VpnConnectionState.Connected)
            } catch (_: Exception) {
                cleanupLocked()
                VpnConnectionStore.update(VpnConnectionState.Failed("VPN connection could not be started"))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun disconnect() = scope.launch {
        mutex.withLock {
            if (!session.active) { VpnConnectionStore.update(VpnConnectionState.Disconnected); stopSelf(); return@withLock }
            VpnConnectionStore.update(VpnConnectionState.Disconnecting)
            cleanupLocked()
            VpnConnectionStore.update(VpnConnectionState.Disconnected)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cleanupLocked() {
        session.cleanup()
    }

    override fun onDestroy() { cleanupLocked(); VpnConnectionStore.update(VpnConnectionState.Disconnected); super.onDestroy() }

    private fun establishTun(): TunHandle? = Builder().setSession("MyVPN").setMtu(1400)
        .addAddress("10.8.0.2", 32).addRoute("0.0.0.0", 0)
        .addAddress("fd00:8::2", 128).addRoute("::", 0)
        .addDnsServer("1.1.1.1").establish()?.let(::ParcelTunHandle)

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("MyVPN")
        .setContentText("VPN connected").setOngoing(true)
        .addAction(0, "Disconnect", PendingIntent.getService(this, 0, disconnectIntent(this), PendingIntent.FLAG_IMMUTABLE))
        .build()

    override fun onCreate() { super.onCreate(); (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL_ID, "MyVPN VPN", NotificationManager.IMPORTANCE_LOW)) }

    companion object {
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
