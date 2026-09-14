package com.myvpn.android.ui

import com.myvpn.android.data.*
import com.myvpn.android.vpn.*
import java.io.IOException
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentFlowTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()

    @Test fun expiredClickLoadsTariffsWithoutReloadingAccess() = runTest {
        val f = Fixture(); advanceUntilIdle()
        assertTrue(f.vm.state.value is MainUiState.Expired)
        val requests = f.accessCalls
        f.vm.openTariffs()
        assertTrue((f.vm.state.value as MainUiState.Tariffs).loading)
        advanceUntilIdle()
        assertEquals(f.source.items, f.screen().items)
        assertEquals(requests, f.accessCalls)
        assertEquals(1, f.source.tariffCalls)
    }

    @Test fun emptyTariffsShowsMessage() = runTest {
        val f = Fixture(); f.source.items = emptyList(); advanceUntilIdle()
        f.vm.openTariffs(); advanceUntilIdle()
        assertTrue(f.screen().items.isEmpty())
        assertEquals("Тарифы пока недоступны", f.screen().message)
    }

    @Test fun tariffNetworkErrorCanBeRetried() = runTest {
        val f = Fixture(); f.source.tariffFailure = IOException(); advanceUntilIdle()
        f.vm.openTariffs(); advanceUntilIdle()
        assertFalse(f.screen().loading)
        assertEquals("Нет подключения к сети", f.screen().message)
        f.source.tariffFailure = null
        f.vm.openTariffs(); advanceUntilIdle()
        assertEquals(f.source.items, f.screen().items)
    }

    @Test fun doubleSelectionCreatesOneCheckoutAndUrlIsConsumedOnce() = runTest {
        val f = Fixture(); advanceUntilIdle(); f.vm.openTariffs(); advanceUntilIdle()
        f.vm.chooseTariff("from-backend"); f.vm.chooseTariff("from-backend")
        assertTrue(f.screen().busy)
        advanceUntilIdle()
        assertEquals(listOf("from-backend"), f.source.checkoutCodes)
        assertEquals(URL, f.screen().checkoutUrl)
        assertEquals(URL, f.vm.consumeCheckoutUrl())
        assertNull(f.vm.consumeCheckoutUrl())
        assertTrue(f.screen().awaitingPayment)
    }

    @Test fun unknownTariffCannotCreateCheckout() = runTest {
        val f = Fixture(); advanceUntilIdle(); f.vm.openTariffs(); advanceUntilIdle()
        f.vm.chooseTariff("invented"); advanceUntilIdle()
        assertTrue(f.source.checkoutCodes.isEmpty())
    }

    @Test fun checkoutFailureLeavesUsableScreen() = runTest {
        val f = Fixture(); f.source.checkoutFailure = IOException(); advanceUntilIdle()
        f.vm.openTariffs(); advanceUntilIdle(); f.vm.chooseTariff("from-backend"); advanceUntilIdle()
        assertFalse(f.screen().busy)
        assertNull(f.screen().checkoutUrl)
        assertEquals("Нет подключения к сети", f.screen().message)
    }

    @Test fun malformedCheckoutUrlNeverLaunches() = runTest {
        val f = Fixture(); f.source.url = "javascript:alert(1)"; advanceUntilIdle()
        f.vm.openTariffs(); advanceUntilIdle(); f.vm.chooseTariff("from-backend"); advanceUntilIdle()
        assertNull(f.screen().checkoutUrl)
        assertNotNull(f.screen().message)
        assertFalse(f.screen().busy)
    }

    @Test fun backReturnsExpiredAndCancelsPendingLoad() = runTest {
        val f = Fixture(); advanceUntilIdle()
        f.vm.openTariffs(); f.vm.closeTariffs(); advanceUntilIdle()
        assertTrue(f.vm.state.value is MainUiState.Expired)
        f.vm.openTariffs(); advanceUntilIdle()
        assertEquals(f.source.items, f.screen().items)
        f.vm.closeTariffs()
        assertTrue(f.vm.state.value is MainUiState.Expired)
    }

    @Test fun activatedPaymentLoadsOrdinaryReadyConfigWithoutStartingVpn() = runTest {
        val f = Fixture(); advanceUntilIdle(); f.vm.openTariffs(); advanceUntilIdle()
        f.source.status = PaymentStatusResponse("SUCCEEDED", paymentStatus = "SUCCEEDED", activationStatus = "ACTIVATED")
        f.access = VpnAccessResponse("READY", "PREMIUM", "config-from-access")
        f.vm.checkPayment(); advanceUntilIdle()
        assertEquals("config-from-access", (f.vm.state.value as MainUiState.Ready).access.configuration)
        assertEquals(2, f.accessCalls)
        assertEquals(0, f.engine.starts)
    }

    @Test fun succeededWithoutActivationDoesNotUnlockVpn() = runTest {
        val f = Fixture(); advanceUntilIdle(); f.vm.openTariffs(); advanceUntilIdle()
        f.source.status = PaymentStatusResponse("SUCCEEDED", paymentStatus = "SUCCEEDED", activationStatus = "PENDING")
        f.vm.checkPayment(); advanceUntilIdle()
        assertTrue(f.screen().message!!.contains("активируется"))
        assertEquals(1, f.accessCalls)
    }

    @Test fun activatedPaymentStillRespectsExpiredAccessResponse() = runTest {
        val f = Fixture(); advanceUntilIdle(); f.vm.openTariffs(); advanceUntilIdle()
        f.source.status = PaymentStatusResponse("ALREADY_SUCCEEDED", paymentStatus = "SUCCEEDED", activationStatus = "ACTIVATED")
        f.vm.checkPayment(); advanceUntilIdle()
        assertTrue(f.vm.state.value is MainUiState.Expired)
    }

    @Test fun browserReturnChecksOnceAndPendingDoesNotPoll() = runTest {
        val f = Fixture(); advanceUntilIdle(); f.vm.openTariffs(); advanceUntilIdle()
        f.vm.chooseTariff("from-backend"); advanceUntilIdle(); f.vm.consumeCheckoutUrl()
        f.vm.onPaymentReturned(); f.vm.onPaymentReturned(); advanceUntilIdle()
        assertEquals(1, f.source.currentCalls)
        assertTrue(f.vm.state.value is MainUiState.Expired)
        assertNotNull(f.vm.paymentCheck.value.message)
        f.vm.onPaymentReturned()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, f.source.currentCalls)
        assertEquals(1, f.accessCalls)
    }

    @Test fun canceledPaymentAllowsChoosingAgainWithoutGrantingAccess() = runTest {
        val f = Fixture(); advanceUntilIdle(); f.vm.openTariffs(); advanceUntilIdle()
        f.source.status = PaymentStatusResponse("CANCELED", paymentStatus = "CANCELED", activationStatus = "NOT_READY")
        f.vm.checkPayment(); advanceUntilIdle()
        assertFalse(f.screen().awaitingPayment)
        assertEquals(1, f.accessCalls)
        f.vm.chooseTariff("from-backend"); advanceUntilIdle()
        assertEquals(1, f.source.checkoutCodes.size)
    }

    @Test fun paymentCheckFailureDoesNotUnlockVpn() = runTest {
        val f = Fixture(); advanceUntilIdle(); f.vm.openTariffs(); advanceUntilIdle()
        f.source.currentFailure = IOException()
        f.vm.checkPayment(); advanceUntilIdle()
        assertFalse(f.screen().busy)
        assertEquals("Нет подключения к сети", f.screen().message)
        assertEquals(1, f.accessCalls)
    }

    @Test fun validAccessKeepsExistingReadyAndConnectedBehavior() = runTest {
        val f = Fixture(); f.access = VpnAccessResponse("READY", "PREMIUM", "config"); advanceUntilIdle()
        assertTrue(f.vm.state.value is MainUiState.Ready)
        f.engine.state.value = VpnConnectionState.Connected; runCurrent()
        assertTrue(f.vm.state.value is MainUiState.Connected)
    }

    @Test fun activeVpnStillSurvivesBackendError() = runTest {
        val f = Fixture(); f.engine.state.value = VpnConnectionState.Connected; f.accessFailure = IOException()
        advanceUntilIdle()
        assertTrue(f.vm.state.value is MainUiState.Connected)
        f.vm.disconnect(); advanceUntilIdle()
        assertEquals(1, f.engine.stops)
        assertTrue(f.vm.state.value is MainUiState.Error)
    }

    @Test fun checkoutUrlValidationRejectsUnsafeAndMalformedValues() {
        listOf(null, "", "http://example.test", "https:///missing", "https://user:pass@example.test", "intent://checkout").forEach {
            assertFalse(validCheckoutUrl(it))
        }
        assertTrue(validCheckoutUrl(URL))
    }

    @Test fun activeTrialOpensSameTariffsAndBackRestoresVpnScreen() = runTest {
        val f = Fixture(); f.access = VpnAccessResponse("READY", "TRIAL", "trial-config"); advanceUntilIdle()
        val original = f.vm.state.value
        assertEquals("Выбрать тариф", tariffActionLabel(f.access))
        f.vm.openTariffs(); advanceUntilIdle()
        assertEquals(f.source.items, f.screen().items)
        assertEquals(1, f.accessCalls)
        f.vm.closeTariffs()
        assertEquals(original, f.vm.state.value)
        f.vm.connect(); f.vm.onPermissionResult(true); advanceUntilIdle()
        assertEquals(1, f.engine.starts)
    }

    @Test fun activePaidOpensSameTariffsAndBackRestoresVpnScreen() = runTest {
        val f = Fixture(); f.access = VpnAccessResponse("READY", "PREMIUM", "paid-config"); advanceUntilIdle()
        val original = f.vm.state.value
        assertEquals("Продлить доступ", tariffActionLabel(f.access))
        f.vm.openTariffs(); advanceUntilIdle()
        assertEquals(f.source.items, f.screen().items)
        f.vm.chooseTariff("from-backend"); advanceUntilIdle()
        assertEquals(listOf("from-backend"), f.source.checkoutCodes)
        f.vm.closeTariffs()
        assertEquals(original, f.vm.state.value)
        assertEquals(0, f.engine.stops)
    }

    @Test fun connectedVpnKeepsRunningThroughTariffsAndBack() = runTest {
        val f = Fixture(); f.access = VpnAccessResponse("READY", "TRIAL", "config")
        f.engine.state.value = VpnConnectionState.Connected; advanceUntilIdle()
        val original = f.vm.state.value
        f.vm.openTariffs(); advanceUntilIdle()
        assertEquals(VpnConnectionState.Connected, f.screen().connection)
        assertEquals(0, f.engine.stops)
        f.vm.closeTariffs()
        assertEquals(original, f.vm.state.value)
        assertEquals(0, f.engine.starts)
    }

    @Test fun tariffErrorRetainsLiveVpnControlsAndBackUsesCurrentEngineState() = runTest {
        val f = Fixture(); f.access = VpnAccessResponse("READY", "PREMIUM", "config")
        f.engine.state.value = VpnConnectionState.Connected; advanceUntilIdle()
        f.source.tariffFailure = IOException()
        f.vm.openTariffs(); advanceUntilIdle()
        assertEquals(VpnConnectionState.Connected, f.screen().connection)
        assertEquals("Нет подключения к сети", f.screen().message)
        f.vm.disconnect(); advanceUntilIdle()
        assertEquals(VpnConnectionState.Disconnected, f.screen().connection)
        assertEquals(1, f.engine.stops)
        f.vm.closeTariffs()
        assertTrue(f.vm.state.value is MainUiState.Ready)
    }

    @Test fun earlyPaymentRefreshesPremiumExpiryWithoutDisconnectingVpn() = runTest {
        val f = Fixture(); f.access = VpnAccessResponse("READY", "TRIAL", "config")
        f.engine.state.value = VpnConnectionState.Connected; advanceUntilIdle()
        f.vm.openTariffs(); advanceUntilIdle()
        f.vm.chooseTariff("from-backend"); advanceUntilIdle(); f.vm.consumeCheckoutUrl()
        f.source.status = PaymentStatusResponse("SUCCEEDED", paymentStatus = "SUCCEEDED", activationStatus = "ACTIVATED")
        f.access = VpnAccessResponse("READY", "PREMIUM", "config", premiumExpiresAt = "2026-10-14T12:00:00Z")
        f.vm.onPaymentReturned(); advanceUntilIdle()
        assertEquals("2026-10-14T12:00:00Z", (f.vm.state.value as MainUiState.Connected).access?.premiumExpiresAt)
        assertEquals(0, f.engine.stops)
        assertEquals(0, f.engine.starts)
        assertEquals(1, f.source.checkoutCodes.size)
    }

    @Test fun backDuringPaymentCheckDoesNotCancelActivatedRefresh() = runTest {
        val f = Fixture(); f.access = VpnAccessResponse("READY", "PREMIUM", "old", premiumExpiresAt = "2026-10-01T00:00:00Z")
        advanceUntilIdle(); f.vm.openTariffs(); advanceUntilIdle()
        f.source.status = PaymentStatusResponse("SUCCEEDED", paymentStatus = "SUCCEEDED", activationStatus = "ACTIVATED")
        f.access = VpnAccessResponse("READY", "PREMIUM", "fresh", premiumExpiresAt = "2026-11-17T12:34:56Z")
        f.vm.checkPayment(); f.vm.closeTariffs(); advanceUntilIdle()
        assertEquals(2, f.accessCalls)
        assertEquals("2026-11-17T12:34:56Z", (f.vm.state.value as MainUiState.Ready).access.premiumExpiresAt)
        assertEquals(1, f.source.currentCalls)
    }

    @Test fun lateTariffBackCannotRestorePrePaymentTrialSnapshot() = runTest {
        val f = Fixture(); f.access = VpnAccessResponse("READY", "TRIAL", "old")
        advanceUntilIdle(); f.vm.openTariffs(); advanceUntilIdle()
        f.source.status = PaymentStatusResponse("SUCCEEDED", paymentStatus = "SUCCEEDED", activationStatus = "ACTIVATED")
        f.access = VpnAccessResponse("READY", "PREMIUM", "fresh", premiumExpiresAt = "2026-11-17T12:34:56Z")
        f.vm.checkPayment(); advanceUntilIdle()
        f.vm.closeTariffs()
        assertEquals("2026-11-17T12:34:56Z", (f.vm.state.value as MainUiState.Ready).access.premiumExpiresAt)
        assertEquals(2, f.accessCalls)
    }

    @Test fun backDuringSlowAccessRefreshCannotRestoreOldPaidMetadata() = runTest {
        val f = Fixture(); f.access = VpnAccessResponse("READY", "PREMIUM", "old", premiumExpiresAt = "2026-10-01T00:00:00Z")
        f.engine.state.value = VpnConnectionState.Connected
        advanceUntilIdle(); f.vm.openTariffs(); advanceUntilIdle()
        f.source.status = PaymentStatusResponse("SUCCEEDED", paymentStatus = "SUCCEEDED", activationStatus = "ACTIVATED")
        val gate = CompletableDeferred<Unit>(); f.accessGate = gate
        f.access = VpnAccessResponse("READY", "PREMIUM", "fresh", premiumExpiresAt = "2027-02-03T04:05:06Z")
        f.vm.checkPayment(); runCurrent()
        f.vm.closeTariffs(); f.vm.checkPayment()
        assertTrue(f.vm.state.value is MainUiState.Connected)
        gate.complete(Unit); advanceUntilIdle()
        f.vm.closeTariffs()
        assertEquals("2027-02-03T04:05:06Z", (f.vm.state.value as MainUiState.Connected).access?.premiumExpiresAt)
        assertEquals(2, f.accessCalls)
        assertEquals(1, f.source.currentCalls)
        assertEquals(0, f.engine.starts)
        assertEquals(0, f.engine.stops)
    }

    @Test fun activatedRefreshFailureRetainsConfirmationAndRetriesOnlyAccessWhileConnected() = runTest {
        val f = Fixture(); f.access = VpnAccessResponse("READY", "TRIAL", "old")
        f.engine.state.value = VpnConnectionState.Connected
        advanceUntilIdle(); f.vm.openTariffs(); advanceUntilIdle()
        f.source.status = PaymentStatusResponse("SUCCEEDED", paymentStatus = "SUCCEEDED", activationStatus = "ACTIVATED")
        f.accessFailure = IOException()
        f.vm.checkPayment(); advanceUntilIdle()
        val failedRefresh = f.vm.state.value as MainUiState.Connected
        assertTrue(failedRefresh.retryable)
        assertTrue(failedRefresh.message!!.contains("Оплата подтверждена"))
        f.vm.closeTariffs()
        assertEquals(failedRefresh, f.vm.state.value)
        f.accessFailure = null
        f.access = VpnAccessResponse("READY", "PREMIUM", "new", premiumExpiresAt = "2027-03-04T05:06:07Z")
        f.vm.retry(); f.vm.retry(); advanceUntilIdle()
        val refreshed = f.vm.state.value as MainUiState.Connected
        assertEquals("2027-03-04T05:06:07Z", refreshed.access?.premiumExpiresAt)
        assertFalse(refreshed.retryable)
        assertNull(refreshed.message)
        assertEquals(3, f.accessCalls)
        assertEquals(1, f.source.currentCalls)
        assertEquals(0, f.engine.starts)
        assertEquals(0, f.engine.stops)
    }

    @Test fun activatedRefreshFailureWhileDisconnectedOffersRetryWithoutRecheckingPayment() = runTest {
        val f = Fixture(); advanceUntilIdle(); f.vm.openTariffs(); advanceUntilIdle()
        f.source.status = PaymentStatusResponse("SUCCEEDED", paymentStatus = "SUCCEEDED", activationStatus = "ACTIVATED")
        f.accessFailure = IOException()
        f.vm.checkPayment(); advanceUntilIdle()
        val error = f.vm.state.value as MainUiState.Error
        assertTrue(error.retryable)
        assertTrue(error.message.contains("Оплата подтверждена"))
        f.accessFailure = null
        f.access = VpnAccessResponse("READY", "PREMIUM", "new", premiumExpiresAt = "2027-03-04T05:06:07Z")
        f.vm.retry(); advanceUntilIdle()
        assertEquals("2027-03-04T05:06:07Z", (f.vm.state.value as MainUiState.Ready).access.premiumExpiresAt)
        assertEquals(1, f.source.currentCalls)
    }

    @Test fun activatedAccessNotReadyDoesNotStartAutomaticPolling() = runTest {
        val f = Fixture(); advanceUntilIdle(); f.vm.openTariffs(); advanceUntilIdle()
        f.source.status = PaymentStatusResponse("SUCCEEDED", paymentStatus = "SUCCEEDED", activationStatus = "ACTIVATED")
        f.access = VpnAccessResponse("PROVISIONING", "PREMIUM")
        f.vm.checkPayment(); advanceUntilIdle()
        assertTrue((f.vm.state.value as MainUiState.Error).retryable)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, f.accessCalls)
        assertEquals(1, f.source.currentCalls)
    }

    @Test fun mainTrialAndPremiumCheckPaymentWithoutLoadingTariffs() = runTest {
        for (entitlement in listOf("TRIAL", "PREMIUM")) {
            val f = Fixture(); f.access = VpnAccessResponse("READY", entitlement, "config"); advanceUntilIdle()
            val original = f.vm.state.value
            f.vm.checkPayment(); f.vm.checkPayment(); advanceUntilIdle()
            assertEquals(original, f.vm.state.value)
            assertEquals(1, f.source.currentCalls)
            assertEquals(0, f.source.tariffCalls)
            assertEquals(1, f.accessCalls)
            assertNotNull(f.vm.paymentCheck.value.message)
        }
    }

    @Test fun mainPaymentStatusesAndErrorsPreserveReadyAndConnected() = runTest {
        for (connected in listOf(false, true)) {
            val f = Fixture(); f.access = VpnAccessResponse("READY", "PREMIUM", "config")
            if (connected) f.engine.state.value = VpnConnectionState.Connected
            advanceUntilIdle()
            val original = f.vm.state.value
            for (status in listOf("PENDING", "FAILED", "CANCELED", "NOT_FOUND")) {
                f.source.status = PaymentStatusResponse(status, paymentStatus = status.takeUnless { it == "NOT_FOUND" })
                f.vm.checkPayment(); advanceUntilIdle()
                assertEquals(original, f.vm.state.value)
                assertNotNull(f.vm.paymentCheck.value.message)
            }
            f.source.currentFailure = IOException()
            f.vm.checkPayment(); advanceUntilIdle()
            assertEquals(original, f.vm.state.value)
            assertEquals("Нет подключения к сети", f.vm.paymentCheck.value.message)
            assertFalse(f.vm.paymentCheck.value.busy)
            f.source.currentFailure = null
            f.vm.checkPayment(); advanceUntilIdle()
            assertEquals(6, f.source.currentCalls)
            assertEquals(1, f.accessCalls)
            assertEquals(0, f.engine.starts)
            assertEquals(0, f.engine.stops)
        }
    }

    @Test fun mainActivatedRefreshesBackendMetadataWithoutRestartingVpn() = runTest {
        val f = Fixture(); f.access = VpnAccessResponse("READY", "PREMIUM", "config")
        f.engine.state.value = VpnConnectionState.Connected; advanceUntilIdle()
        f.source.status = PaymentStatusResponse("SUCCEEDED", paymentStatus = "SUCCEEDED", activationStatus = "ACTIVATED")
        f.access = f.access.copy(premiumExpiresAt = "2027-03-04T05:06:07Z")
        f.vm.checkPayment(); advanceUntilIdle()
        assertEquals(f.access, (f.vm.state.value as MainUiState.Connected).access)
        assertEquals(2, f.accessCalls)
        assertEquals(0, f.engine.starts)
        assertEquals(0, f.engine.stops)
    }

    @Test fun checkoutReturnRestoresMainOnceAndManualCheckRemainsAvailable() = runTest {
        for (entitlement in listOf("TRIAL", "PREMIUM")) {
            val f = Fixture(); f.access = VpnAccessResponse("READY", entitlement, "config"); advanceUntilIdle()
            val original = f.vm.state.value
            f.vm.openTariffs(); advanceUntilIdle()
            f.vm.chooseTariff("from-backend"); advanceUntilIdle()
            assertEquals(URL, f.vm.consumeCheckoutUrl())
            f.vm.onPaymentReturned(); advanceUntilIdle()
            assertEquals(original, f.vm.state.value)
            f.vm.onPaymentReturned(); advanceUntilIdle()
            assertEquals(1, f.source.currentCalls)
            f.vm.checkPayment(); advanceUntilIdle()
            assertEquals(2, f.source.currentCalls)
            f.vm.closeTariffs()
            assertEquals(original, f.vm.state.value)
        }
    }

    @Test fun failedBrowserLaunchDoesNotTriggerAutomaticCheck() = runTest {
        val f = Fixture(); advanceUntilIdle(); f.vm.openTariffs(); advanceUntilIdle()
        f.vm.chooseTariff("from-backend"); advanceUntilIdle(); f.vm.consumeCheckoutUrl()
        f.vm.checkoutOpenFailed(); f.vm.onPaymentReturned(); advanceUntilIdle()
        assertFalse(f.screen().awaitingPayment)
        assertEquals(0, f.source.currentCalls)
    }

    @Test fun slowMainCheckDoesNotRestoreConnectedAfterUserDisconnects() = runTest {
        val f = Fixture(); f.access = VpnAccessResponse("READY", "PREMIUM", "config")
        f.engine.state.value = VpnConnectionState.Connected; advanceUntilIdle()
        val gate = CompletableDeferred<Unit>(); f.source.currentGate = gate
        f.vm.checkPayment(); runCurrent()
        assertTrue(f.vm.paymentCheck.value.busy)
        f.vm.openTariffs(); f.vm.checkPayment()
        f.vm.disconnect(); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertTrue(f.vm.state.value is MainUiState.Ready)
        assertEquals(1, f.source.currentCalls)
        assertEquals(0, f.source.tariffCalls)
        assertEquals(1, f.engine.stops)
    }

    @Test fun mainBackendServerErrorKeepsConnectedAndAllowsRetry() = runTest {
        val f = Fixture(); f.access = VpnAccessResponse("READY", "PREMIUM", "config")
        f.engine.state.value = VpnConnectionState.Connected; advanceUntilIdle()
        val original = f.vm.state.value
        f.source.currentFailure = retrofit2.HttpException(retrofit2.Response.error<Any>(503,
            "unavailable".toResponseBody()))
        f.vm.checkPayment(); advanceUntilIdle()
        assertEquals(original, f.vm.state.value)
        assertEquals("Сервис временно недоступен", f.vm.paymentCheck.value.message)
        assertFalse(f.vm.paymentCheck.value.busy)
        assertEquals(0, f.engine.stops)
    }

    @Test fun browsingTariffsThenImmediateConnectMatchesDirectConnect() = runTest {
        for (browse in listOf(false, true)) {
            val f = Fixture(); f.access = VpnAccessResponse("READY", "PREMIUM", "same-config"); advanceUntilIdle()
            val original = f.vm.state.value
            if (browse) {
                f.vm.openTariffs(); advanceUntilIdle(); f.vm.closeTariffs()
                assertEquals(original, f.vm.state.value)
            }
            // No scheduler turn between Back and Connect/permission result.
            f.vm.connect()
            assertEquals(MainUiState.AwaitingPermission, f.vm.state.value)
            f.vm.onPermissionResult(true); advanceUntilIdle()
            assertEquals(listOf("same-config"), f.engine.configurations)
            assertEquals(1, f.engine.starts)
            assertEquals(1, f.accessCalls)
            assertEquals(0, f.source.currentCalls)
            assertTrue(f.source.checkoutCodes.isEmpty())
            f.engine.state.value = VpnConnectionState.Connected; runCurrent()
            assertEquals(f.access, (f.vm.state.value as MainUiState.Connected).access)
        }
    }

    @Test fun browsingBackAndActivityResumeNeverChecksPayment() = runTest {
        val f = Fixture(); f.access = VpnAccessResponse("READY", "PREMIUM", "config"); advanceUntilIdle()
        f.vm.openTariffs(); advanceUntilIdle(); assertNull(f.vm.consumeCheckoutUrl())
        f.vm.closeTariffs(); f.vm.onPaymentReturned(); advanceUntilIdle()
        assertEquals(0, f.source.currentCalls)
        assertTrue(f.source.checkoutCodes.isEmpty())
        assertEquals(PaymentCheckState(), f.vm.paymentCheck.value)
        assertEquals(f.access, (f.vm.state.value as MainUiState.Ready).access)
    }

    @Test fun backCancelsSuspendedTariffLoadWithoutBlockingImmediateConnect() = runTest {
        val f = Fixture(); f.access = VpnAccessResponse("READY", "PREMIUM", "config"); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>(); f.source.tariffGate = gate
        f.vm.openTariffs(); runCurrent(); assertTrue(f.screen().loading)
        f.vm.closeTariffs(); f.vm.connect(); f.vm.onPermissionResult(true)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, f.engine.starts)
        assertTrue(f.vm.state.value is MainUiState.Connecting)
        assertEquals(0, f.source.currentCalls)
        assertEquals(1, f.accessCalls)
    }

    @Test fun repeatedBrowseAndBackKeepsConfigurationAndEngineUsable() = runTest {
        val f = Fixture(); f.access = VpnAccessResponse("READY", "PREMIUM", "config"); advanceUntilIdle()
        repeat(5) {
            f.vm.openTariffs(); advanceUntilIdle(); f.vm.closeTariffs()
            assertEquals(f.access, (f.vm.state.value as MainUiState.Ready).access)
        }
        f.vm.connect(); f.vm.onPermissionResult(true); advanceUntilIdle()
        assertEquals(1, f.engine.starts)
        assertEquals(5, f.source.tariffCalls)
        assertEquals(0, f.source.currentCalls)
        assertEquals(1, f.accessCalls)
    }

    @Test fun backDuringLoadingThenImmediateReopenHasNoStaleJobGuard() = runTest {
        val f = Fixture(); f.access = VpnAccessResponse("READY", "PREMIUM", "config"); advanceUntilIdle()
        f.source.tariffGate = CompletableDeferred()
        f.vm.openTariffs(); runCurrent(); f.vm.closeTariffs()
        f.source.tariffGate = null
        f.vm.openTariffs(); advanceUntilIdle()
        assertEquals(f.source.items, f.screen().items)
        f.vm.closeTariffs(); f.vm.connect(); f.vm.onPermissionResult(true); advanceUntilIdle()
        assertEquals(1, f.engine.starts)
    }

    private class Fixture {
        val source = FakePayments()
        var access = VpnAccessResponse("READY", "EXPIRED")
        var accessFailure: Exception? = null
        var accessGate: CompletableDeferred<Unit>? = null
        var accessCalls = 0
        val engine = Engine()
        val vm = MainViewModel(object : PhoneAuthSource {
            override suspend fun restoreSession() = Session("access", "refresh", 3600, "account", "EXPIRED", null)
            override suspend fun startVerification(phone: String): PhoneVerificationStartResponse = error("unused")
            override suspend fun verificationStatus(verificationId: String): PhoneVerificationStatusResponse = error("unused")
            override suspend fun exchange(verificationId: String, exchangeToken: String): Session = error("unused")
        }, object : VpnAccessSource {
            override suspend fun current(): VpnAccessResponse { accessCalls++; accessGate?.await(); accessFailure?.let { throw it }; return access }
        }, engine, payments = source)
        fun screen() = vm.state.value as MainUiState.Tariffs
    }

    private class Engine : VpnEngine {
        override val state = MutableStateFlow<VpnConnectionState>(VpnConnectionState.Disconnected)
        var starts = 0; var stops = 0
        val configurations = mutableListOf<String>()
        override suspend fun start(configuration: String) { starts++; configurations += configuration; state.value = VpnConnectionState.Connecting }
        override suspend fun stop() { stops++; state.value = VpnConnectionState.Disconnected }
    }

    private class FakePayments : PaymentSource {
        var items = listOf(TariffResponse("id", "from-backend", "Backend name", durationDays = 42, price = BigDecimal("123.45"), currency = "RUB"))
        var tariffCalls = 0; var currentCalls = 0
        var currentGate: CompletableDeferred<Unit>? = null
        var tariffGate: CompletableDeferred<Unit>? = null
        val checkoutCodes = mutableListOf<String>()
        var tariffFailure: Exception? = null; var checkoutFailure: Exception? = null; var currentFailure: Exception? = null
        var url = URL
        var status = PaymentStatusResponse("STILL_PENDING", paymentStatus = "PENDING", activationStatus = "NOT_READY")
        override suspend fun tariffs(): List<TariffResponse> { tariffCalls++; tariffGate?.await(); tariffFailure?.let { throw it }; return items }
        override suspend fun checkout(code: String): CheckoutResponse {
            checkoutCodes += code; checkoutFailure?.let { throw it }
            return CheckoutResponse("order", "Backend name", BigDecimal("123.45"), "RUB", 42, "PENDING", url)
        }
        override suspend fun current(): PaymentStatusResponse { currentCalls++; currentGate?.await(); currentFailure?.let { throw it }; return status }
    }
    companion object { private const val URL = "https://checkout.example.test/payment" }
}
