package com.myvpn.android.ui

import com.myvpn.android.data.AppVersionResponse
import com.myvpn.android.data.AppVersionSource
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun currentOrNewerVersionNeedsNoUpdate() {
        assertEquals(UpdateDecision.None, UpdatePolicy.decide(1, metadata(latest = 1)))
        assertEquals(UpdateDecision.None, UpdatePolicy.decide(2, metadata(latest = 1)))
    }

    @Test fun supportedOlderVersionGetsOptionalUpdate() {
        val decision = UpdatePolicy.decide(2, metadata(latest = 3, minimum = 2))
        assertTrue(decision is UpdateDecision.Optional)
    }

    @Test fun unsupportedVersionGetsMandatoryUpdateIncludingWhenMinimumEqualsLatest() {
        assertTrue(UpdatePolicy.decide(1, metadata(latest = 3, minimum = 2)) is UpdateDecision.Mandatory)
        assertTrue(UpdatePolicy.decide(1, metadata(latest = 2, minimum = 2)) is UpdateDecision.Mandatory)
    }

    @Test fun malformedOrNonHttpsMetadataDoesNotBlockApp() {
        assertEquals(UpdateDecision.None, UpdatePolicy.decide(1, metadata(latest = 0)))
        assertEquals(UpdateDecision.None, UpdatePolicy.decide(1, metadata(latest = 2, minimum = 3)))
        assertEquals(UpdateDecision.None, UpdatePolicy.decide(1, metadata(apkUrl = "file:///tmp/myvpn.apk")))
    }

    @Test fun networkFailureFailsOpenAndOptionalLaterClosesDialog() = runTest {
        val failed = UpdateViewModel(FakeVersionSource(error = IOException("offline")), 1, UpdateDiagnostics { })
        advanceUntilIdle()
        assertEquals(UpdateDecision.None, failed.decision.value)

        val optional = UpdateViewModel(FakeVersionSource(metadata = metadata(latest = 2, minimum = 1)), 1)
        advanceUntilIdle()
        assertTrue(optional.decision.value is UpdateDecision.Optional)
        optional.dismissOptional()
        assertEquals(UpdateDecision.None, optional.decision.value)
    }

    @Test fun mandatoryUpdateCannotBeDismissedAndOnlyHttpsActionIsCreated() = runTest {
        val mandatory = UpdateViewModel(FakeVersionSource(metadata = metadata(latest = 2, minimum = 2)), 1)
        advanceUntilIdle()
        mandatory.dismissOptional()
        assertTrue(mandatory.decision.value is UpdateDecision.Mandatory)
        assertEquals("https://api.myvpn05.ru/downloads/myvpn-1.0.0.apk", updateUrl("https://api.myvpn05.ru/downloads/myvpn-1.0.0.apk"))
        assertNull(updateUrl("intent://untrusted"))
    }

    private fun metadata(latest: Long = 2, minimum: Long = 1, apkUrl: String = "https://api.myvpn05.ru/downloads/myvpn-1.0.0.apk") =
        AppVersionResponse(latest, "1.0.0", minimum, apkUrl, "changes")

    private class FakeVersionSource(private val metadata: AppVersionResponse? = null, private val error: Throwable? = null) : AppVersionSource {
        override suspend fun current(): AppVersionResponse { error?.let { throw it }; return metadata ?: throw IllegalStateException("missing metadata") }
    }
}
