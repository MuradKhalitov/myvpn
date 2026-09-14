package com.myvpn.android.vpn

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VpnServiceLifecycleTest {
    @Test fun connectThenDisconnectStopsCoreBeforeClosingTun() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.connect(1)
        advanceUntilIdle()
        assertTrue(f.session.active)
        f.disconnect(2)
        advanceUntilIdle()
        assertEquals(listOf("foreground", "tun", "run", "stop", "close", "finish:2:true", "remove"), f.events)
        assertEquals(listOf(VpnConnectionState.Connecting, VpnConnectionState.Connected,
            VpnConnectionState.Disconnecting, VpnConnectionState.Disconnected), f.states)
        assertFalse(f.session.active)
        f.lifecycle.destroy().join()
    }

    @Test fun duplicateConnectCreatesOnlyOneTunAndCore() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.connect(1)
        f.connect(2)
        assertEquals(listOf("foreground", "foreground"), f.events)
        advanceUntilIdle()
        assertEquals(1, f.events.count { it == "tun" })
        assertEquals(1, f.events.count { it == "run" })
        f.lifecycle.destroy().join()
    }

    @Test fun duplicateDisconnectAndCleanupAreSafe() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.connect(1)
        advanceUntilIdle()
        f.disconnect(2)
        f.disconnect(3)
        advanceUntilIdle()
        val cleanup = f.lifecycle.destroy()
        assertSame(cleanup, f.lifecycle.destroy())
        cleanup.join()
        assertEquals(1, f.events.count { it == "stop" })
        assertEquals(1, f.events.count { it == "close" })
        assertFalse(f.session.active)
    }

    @Test fun rapidConnectDisconnectKeepsDeliveryOrder() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.connect(1)
        f.disconnect(2)
        // Submission must not execute native work inline on the caller thread.
        assertEquals(listOf("foreground"), f.events)
        advanceUntilIdle()
        assertEquals(listOf("foreground", "tun", "run", "stop", "close", "finish:2:true", "remove"), f.events)
        assertFalse(f.session.active)
        f.lifecycle.destroy().join()
    }

    @Test fun newerConnectSurvivesOlderDisconnectWithoutOverlappingCores() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.connect(1)
        advanceUntilIdle()
        f.disconnect(2)
        f.connect(3)
        advanceUntilIdle()
        assertEquals(listOf("foreground", "tun", "run", "foreground", "stop", "close", "finish:2:false",
            "tun", "run"), f.events)
        assertTrue(f.session.active)
        assertEquals(VpnConnectionState.Connected, f.states.last())
        f.lifecycle.destroy().join()
    }

    @Test fun acceptedStopRejectsWorkWhileWaitingForDestroy() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.connect(1)
        advanceUntilIdle()
        f.disconnect(2)
        advanceUntilIdle()
        assertNull(f.connect(3))
        assertNull(f.disconnect(4))
        f.lifecycle.destroy().join()
        assertEquals(1, f.events.count { it == "run" })
    }

    @Test fun destroyAfterConnectCleansSessionAndRejectsNewCommands() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.connect(1)
        advanceUntilIdle()
        f.lifecycle.destroy().join()
        assertNull(f.connect(2))
        assertNull(f.disconnect(3))
        assertFalse(f.session.active)
        assertEquals(VpnConnectionState.Disconnected, f.states.last())
        assertEquals(1, f.events.count { it == "stop" })
    }

    @Test fun destroyCancelsQueuedServiceCoroutinesBeforeNativeStart() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val first = f.connect(1)!!
        val second = f.connect(2)!!
        val disconnect = f.disconnect(3)!!
        f.lifecycle.destroy().join()
        advanceUntilIdle()
        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
        assertTrue(disconnect.isCancelled)
        assertEquals(listOf("foreground", "foreground", "remove"), f.events)
        assertEquals(listOf(VpnConnectionState.Disconnected), f.states)
        assertFalse(f.session.active)
    }

    @Test fun foregroundFailureEndsInFailedAndStopsService() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler), failForeground = true)
        f.connect(1)
        assertTrue(f.states.last() is VpnConnectionState.Failed)
        assertTrue(f.events.contains("finish:1:true"))
        advanceUntilIdle()
        assertEquals(listOf("foreground", "finish:1:true", "remove", "remove"), f.events)
        assertFalse(f.session.active)
        f.lifecycle.destroy().join()
        assertEquals(VpnConnectionState.Disconnected, f.states.last())
    }

    @Test fun nativeStartFailureCleansCoreAndTun() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler), onRun = { error("native start failed") })
        f.connect(1)
        advanceUntilIdle()
        assertEquals(listOf("foreground", "tun", "run", "stop", "close", "finish:1:true", "remove"), f.events)
        assertTrue(f.states.last() is VpnConnectionState.Failed)
        assertFalse(f.session.active)
        f.lifecycle.destroy().join()
    }

    @Test fun destroyDuringNativeStartWaitsWithoutBlockingCallerAndCancelsQueuedWork() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val f = Fixture(Dispatchers.IO, onRun = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        })
        val connect = f.connect(1)!!
        try {
            assertEquals("foreground", f.events.first())
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val queued = f.disconnect(2)!!
            val cleanup = f.lifecycle.destroy()
            assertFalse(cleanup.isCompleted)
            assertFalse(f.events.contains("stop"))
            assertFalse(f.events.contains("close"))
            assertNull(f.connect(3))
            release.countDown()
            awaitWorkerJobs(cleanup, connect, queued)
            assertTrue(connect.isCancelled)
            assertTrue(queued.isCancelled)
            assertEquals(listOf("foreground", "tun", "run", "foreground", "finish:3:true", "remove",
                "stop", "close", "remove"), f.events)
            assertFalse(f.states.contains(VpnConnectionState.Connected))
            assertEquals(VpnConnectionState.Disconnected, f.states.last())
            assertFalse(f.session.active)
        } finally {
            release.countDown()
            awaitWorkerJobs(f.lifecycle.destroy())
        }
    }

    @Test fun replacementServiceWaitsForOldNativeStartAndCleanup() = runTest {
        val mutex = Mutex()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val old = Fixture(Dispatchers.IO, mutex = mutex, events = events, onRun = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        })
        val replacement = Fixture(Dispatchers.IO, mutex = mutex, events = events)
        old.connect(1)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val cleanup = old.lifecycle.destroy()
            val restarted = replacement.connect(1)!!
            assertFalse(restarted.isCompleted)
            assertEquals(2, events.count { it == "foreground" })
            release.countDown()
            awaitWorkerJobs(cleanup, restarted)
            assertEquals(listOf("foreground", "tun", "run", "foreground", "stop", "close", "remove",
                "tun", "run"), events)
            assertFalse(old.session.active)
            assertTrue(replacement.session.active)
        } finally {
            release.countDown()
            awaitWorkerJobs(old.lifecycle.destroy(), replacement.lifecycle.destroy())
        }
    }

    @Test fun foregroundDoesNotWaitForMutexOrCoroutineDispatch() = runTest {
        val mutex = Mutex(locked = true)
        val f = Fixture(StandardTestDispatcher(testScheduler), mutex = mutex)
        val connect = f.connect(1)!!
        assertEquals(listOf("foreground"), f.events)
        assertFalse(connect.isCompleted)
        advanceUntilIdle()
        assertEquals(listOf("foreground"), f.events)
        mutex.unlock()
        advanceUntilIdle()
        assertEquals(listOf("foreground", "tun", "run"), f.events)
        f.lifecycle.destroy().join()
    }

    @Test fun promotionFailureStopsImmediatelyEvenWhenMutexIsHeld() = runTest {
        val mutex = Mutex(locked = true)
        val f = Fixture(StandardTestDispatcher(testScheduler), mutex = mutex, failForeground = true)
        val cleanup = f.connect(1)!!
        assertTrue(f.events.contains("finish:1:true"))
        assertTrue(f.states.last() is VpnConnectionState.Failed)
        assertFalse(cleanup.isCompleted)
        assertFalse(f.events.contains("tun"))
        mutex.unlock()
        cleanup.join()
        assertFalse(f.session.active)
    }

    @Test fun replacementPromotesWhileOldCleanupIsBlockedInsideNativeStop() = runTest {
        val mutex = Mutex()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val old = Fixture(Dispatchers.IO, mutex = mutex, onStop = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        })
        val replacement = Fixture(Dispatchers.IO, mutex = mutex)
        awaitWorkerJobs(old.connect(1)!!)
        try {
            val cleanup = old.lifecycle.destroy()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val connect = replacement.connect(1)!!
            assertEquals(listOf("foreground"), replacement.events)
            assertFalse(connect.isCompleted)
            release.countDown()
            awaitWorkerJobs(cleanup, connect)
            assertTrue(replacement.session.active)
        } finally {
            release.countDown()
            awaitWorkerJobs(old.lifecycle.destroy(), replacement.lifecycle.destroy())
        }
    }

    // Real IO threads need a wall-clock timeout, not runTest's virtual-time timeout.
    private suspend fun awaitWorkerJobs(vararg jobs: Job) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { jobs.forEach { it.join() } }
    }

    private class Fixture(
        dispatcher: CoroutineDispatcher,
        mutex: Mutex = Mutex(),
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf()),
        failForeground: Boolean = false,
        onRun: () -> Unit = {},
        onStop: () -> Unit = {}
    ) {
        val states: MutableList<VpnConnectionState> = Collections.synchronizedList(mutableListOf())
        @Volatile private var latestStartId = 0
        val session = VpnServiceSession {
            LibXrayCoreEngine(object : LibXrayBridge {
                override fun setTunFd(fd: Int) = Unit
                override fun registerSocketProtection(protect: (Long) -> Boolean) = Unit
                override fun initDns(protect: (Long) -> Boolean, server: String) = Unit
                override fun runXrayFromJson(config: String) { events += "run"; onRun() }
                override fun stopXray() { events += "stop"; onStop() }
                override fun isRunning() = true
                override fun resetDns() = Unit
            })
        }
        val lifecycle = VpnServiceLifecycle(
            session, mutex,
            establishTun = {
                events += "tun"
                object : TunHandle {
                    override val fd = 42
                    override fun close() { events += "close" }
                }
            },
            protect = { true },
            promoteForeground = { events += "foreground"; check(!failForeground) },
            removeForeground = { events += "remove" },
            stopService = { id -> (id == latestStartId).also { events += "finish:$id:$it" } },
            updateState = { states += it },
            dispatcher = dispatcher
        )
        fun connect(id: Int) = run { latestStartId = id; lifecycle.connect(CONFIG, id) }
        fun disconnect(id: Int) = run { latestStartId = id; lifecycle.disconnect(id) }
    }

    private companion object {
        const val CONFIG = "vless://123e4567-e89b-12d3-a456-426614174000@vpn.example.test:443?type=tcp&security=reality&encryption=none&sni=s&fp=chrome&pbk=p&sid=a"
    }
}
