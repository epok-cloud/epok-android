package dev.epok.rum

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConsentSessionTest {
    @get:Rule val temp = TemporaryFolder()
    private fun file() = File(temp.root, "ledger.json")

    @Test fun deniedDefaultConstructsNothingAndCapturesNothing() = runBlocking {
        val h = Harness(file())
        h.rum.recordHttp("https://api.getepok.dev/path")
        h.rum.recordMetric("sample", 1.0)
        h.rum.flush()
        val prepared = h.rum.prepare(Request.Builder().url("https://api.getepok.dev/path").build())
        assertNull(prepared.request.header("traceparent"))
        assertNull(prepared.request.header("baggage"))
        assertNull(prepared.capture)
        assertNull(h.rum.snapshot().sessionId)
        assertEquals(0, h.constructions)
        assertFalse(file().exists())
        assertEquals(0L, h.rum.snapshot().counters.attempted)
        assertTrue(h.transport.sent.isEmpty())
    }

    @Test fun withdrawalPurgesDiskAndMemoryAndRegrantGetsNewSession() {
        val h = Harness(file())
        h.rum.grantConsent()
        val firstSession = h.rum.snapshot().sessionId
        h.rum.recordHttp("https://api.getepok.dev/orders/42")
        assertEquals(1, FileQueueStore(file()).load().batches.size)
        h.rum.withdrawConsent()
        assertNull(h.rum.snapshot().sessionId)
        assertFalse(h.rum.snapshot().consent)
        assertEquals(0, h.rum.snapshot().pending)
        assertEquals(0, FileQueueStore(file()).load().batches.size)
        assertFalse(file().readText().contains("orders"))
        assertEquals(1L, h.rum.snapshot().counters.dropped_error)
        h.rum.grantConsent()
        assertNotEquals(firstSession, h.rum.snapshot().sessionId)
        assertEquals(0, h.rum.snapshot().pending)
        h.rum.recordMetric("sample", 1.0)
        assertEquals(2L, h.rum.snapshot().counters.attempted)
        h.assertAccounted()
    }

    @Test fun deniedNewProcessLeavesDormantQueueUntilExplicitGrantOrDeny() {
        val first = Harness(file())
        first.rum.grantConsent()
        first.rum.recordMetric("sample", 1.0)
        val id = first.rum.snapshot().batchIds.single()
        val reloaded = Harness(file()) // No close/discard on the prior instance.
        assertFalse(reloaded.rum.snapshot().consent)
        assertEquals(0, reloaded.constructions)
        assertEquals(id, FileQueueStore(file()).load().batches.single().id)
        reloaded.rum.denyConsent()
        assertTrue(FileQueueStore(file()).load().batches.isEmpty())
        assertEquals(1L, reloaded.rum.snapshot().counters.dropped_error)
        reloaded.assertAccounted()
    }

    @Test fun sessionRotatesOnInactivityAndHardCap() {
        val h = Harness(file(), config = Harness.config().copy(sessionInactivityMs = 100, sessionMaxDurationMs = 250))
        h.rum.grantConsent()
        val first = h.rum.snapshot().sessionId
        h.time += 101
        h.rum.recordMetric("sample", 1.0)
        val second = h.rum.snapshot().sessionId
        assertNotEquals(first, second)
        repeat(3) { h.time += 90; h.rum.recordMetric("sample", 1.0) }
        assertNotEquals(second, h.rum.snapshot().sessionId)
        h.assertAccounted()
    }

    @Test fun factoryFailureDoesNotThrowOrOpenGateAndBadObservationIsCounted() {
        val rum = Rum(Harness.config(), FileQueueStore(file()), ManualConnectivity(), { error("factory failed") }, automaticFlush = false)
        rum.grantConsent()
        assertFalse(rum.snapshot().consent)
        assertFalse(rum.snapshot().exporterConstructed)
        rum.withdrawConsent()
        val h = Harness(file())
        h.rum.grantConsent()
        h.rum.recordMetric("bad", Double.NaN)
        h.rum.recordMetric("https://private/secret", 1.0)
        assertEquals(2L, h.rum.snapshot().counters.attempted)
        assertEquals(2L, h.rum.snapshot().counters.dropped_error)
        assertEquals(0, h.rum.snapshot().pending)
        h.assertAccounted()
    }

    @Test fun erasureStubCannotAcceptAnIngestCredentialOrSendARequest() {
        assertEquals(SessionErasure.Result.REQUIRES_SESSION_SCOPED_AUTHORIZATION, SessionErasure.request())
        assertTrue(SessionErasure::class.java.declaredMethods.filter { it.name == "request" }.all { it.parameterCount == 0 })
    }

    @Test fun connectivityListenerStartsOnlyAfterGrantAndThrowingStopCannotKeepConsentAlive() {
        var starts = 0
        var stops = 0
        val monitor = object : Connectivity {
            override fun isOnline() = false
            override fun start(onChange: () -> Unit) { starts++ }
            override fun stop() { stops++; error("platform callback failed") }
        }
        val rum = Rum(Harness.config(), FileQueueStore(file()), monitor, { ScriptedTransport() })
        assertEquals(0, starts)
        rum.grantConsent()
        assertEquals(1, starts)
        rum.recordMetric("sample", 1.0)
        rum.withdrawConsent()
        assertEquals(1, stops)
        assertFalse(rum.snapshot().consent)
        assertFalse(rum.snapshot().exporterConstructed)
        assertEquals(0, rum.snapshot().pending)
        assertEquals(1L, rum.snapshot().counters.dropped_error)
    }
}
