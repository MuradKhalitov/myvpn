package com.myvpn.android.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/** Serializes service commands and final cleanup without blocking lifecycle callbacks. */
internal class VpnServiceLifecycle(
    private val session: VpnServiceSession,
    private val mutex: Mutex,
    private val establishTun: () -> TunHandle?,
    private val protect: (Long) -> Boolean,
    private val promoteForeground: () -> Unit,
    private val removeForeground: () -> Unit,
    private val stopService: (Int) -> Boolean,
    private val updateState: (VpnConnectionState) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    @Volatile private var destroyed = false
    @Volatile private var stopping = false
    private var cleanupJob: Job? = null

    fun connect(configuration: String, startId: Int): Job? = submit {
        if (session.active) return@submit
        updateState(VpnConnectionState.Connecting)
        try {
            promoteForeground()
            currentCoroutineContext().ensureActive()
            check(session.connect(configuration, establishTun, protect))
            // Native start is synchronous: cancellation cannot interrupt it safely.
            currentCoroutineContext().ensureActive()
            updateState(VpnConnectionState.Connected)
        } catch (cancelled: CancellationException) {
            throw cancelled // destroy owns the serialized final cleanup
        } catch (_: Exception) {
            session.cleanup()
            if (!destroyed) {
                updateState(VpnConnectionState.Failed("VPN connection could not be started"))
                finish(startId)
            }
        }
    }

    fun disconnect(startId: Int): Job? = submit {
        if (session.active) updateState(VpnConnectionState.Disconnecting)
        session.cleanup()
        updateState(VpnConnectionState.Disconnected)
        finish(startId)
    }

    private fun finish(startId: Int) {
        try {
            removeForeground()
        } finally {
            // An older Disconnect must not stop a newer delivered Connect.
            if (stopService(startId)) stopping = true
        }
    }

    @Synchronized
    private fun submit(operation: suspend () -> Unit): Job? {
        if (destroyed || stopping) return null
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Register for the existing mutex in command-delivery order, before dispatch.
            mutex.withLock {
                yield() // Native work runs on IO, never inline in onStartCommand.
                if (!destroyed && !stopping) operation()
            }
        }
    }

    @Synchronized
    fun destroy(): Job {
        cleanupJob?.let { return it }
        destroyed = true
        scope.cancel()
        // One independent cleanup job survives cancellation of service work. It waits
        // for any in-flight native call, also before a replacement service can start.
        val cleanupScope = CoroutineScope(dispatcher)
        return cleanupScope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                yield()
                session.cleanup()
                updateState(VpnConnectionState.Disconnected)
                removeForeground()
            }
        }.also { job ->
            cleanupJob = job
            job.invokeOnCompletion { cleanupScope.cancel() }
        }
    }
}
