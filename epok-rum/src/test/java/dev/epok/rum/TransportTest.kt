package dev.epok.rum

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransportTest {
    @get:Rule val temp = TemporaryFolder()
    private fun file(name: String = "queue.json") = File(temp.root, name)

    @Test fun retriesRetainBufferAndStableIdAndCountObservationsOnce() = runBlocking {
        val transport = ScriptedTransport(Outcome(503), Outcome(429, "2"), Outcome(204))
        val h = Harness(file(), transport)
        h.rum.grantConsent()
        h.rum.recordHttp("https://api.getepok.dev/users/42")
        val id = h.rum.snapshot().batchIds.single()
        h.network.setOnline(true)
        h.rum.flush()
        assertEquals(1, h.rum.snapshot().pending)
        assertEquals(0L, h.rum.snapshot().counters.acked)
        assertEquals(1, FileQueueStore(file()).load().batches.size)
        h.time += 100
        h.rum.flush()
        assertEquals(1, h.rum.snapshot().pending)
        h.time += 1_999
        h.rum.flush()
        assertEquals(2, transport.sent.size) // Retry-After cannot be shortened to the 500 ms cap.
        h.time++
        h.rum.flush()
        assertEquals(listOf(id, id, id), transport.sent.map { it.id })
        assertEquals(1, transport.sent.map { it.body }.distinct().size)
        assertEquals(0, h.rum.snapshot().pending)
        assertEquals(DeliveryCounters(attempted = 1, acked = 1), h.rum.snapshot().counters)
        assertTrue(FileQueueStore(file()).load().batches.isEmpty())
        h.rum.flush()
        h.assertAccounted()
    }

    @Test fun persistentReloadKeepsIdsCountersAttemptCapAndRetryDeadline() = runBlocking {
        val first = Harness(file(), ScriptedTransport(Outcome(0)))
        first.rum.grantConsent()
        first.rum.recordMetric("sample", 1.0)
        first.network.setOnline(true)
        first.rum.flush()
        val stored = FileQueueStore(file()).load().batches.single().copy()
        val next = Harness(file(), ScriptedTransport(Outcome(500), Outcome(500)))
        next.rum.grantConsent()
        assertEquals(listOf(stored.id), next.rum.snapshot().batchIds)
        assertEquals(1L, next.rum.snapshot().counters.attempted)
        next.network.setOnline(true)
        next.rum.flush()
        assertTrue(next.transport.sent.isEmpty())
        next.time = stored.notBefore
        next.rum.flush()
        assertEquals(stored.body, next.transport.sent.single().body)
        next.time += 200
        next.rum.flush()
        assertEquals(2, next.transport.sent.size) // Three TOTAL attempts, including the prior process.
        assertEquals(DeliveryCounters(attempted = 1, dropped_error = 1), next.rum.snapshot().counters)
        next.time += 100_000
        next.rum.flush()
        assertEquals(2, next.transport.sent.size)
        val third = Harness(file())
        third.rum.grantConsent()
        assertEquals(next.rum.snapshot().counters, third.rum.snapshot().counters)
        next.assertAccounted()
    }

    @Test fun permanent4xxAndRedirectDropOnceAndNeverRetry() = runBlocking {
        for (status in listOf(400, 401, 403, 404, 408, 302)) {
            val h = Harness(file("$status.json"), ScriptedTransport(Outcome(status)))
            h.rum.grantConsent(); h.rum.recordMetric("sample", 1.0); h.network.setOnline(true)
            h.rum.flush(); h.time += 100_000; h.rum.flush()
            assertEquals(1, h.transport.sent.size)
            assertEquals(DeliveryCounters(attempted = 1, dropped_error = 1), h.rum.snapshot().counters)
            h.assertAccounted()
        }
    }

    @Test fun offlineAgeAndByteLimitsDropOldestWithHonestReasons() = runBlocking {
        val measure = Harness(file("measure.json"))
        measure.rum.grantConsent(); measure.rum.recordMetric("sample", 1.0)
        val h = Harness(file(), config = Harness.config().copy(
            maxQueueBytes = measure.rum.snapshot().queuedBytes + 128, maxQueueAgeMs = 1_000))
        h.rum.grantConsent(); h.rum.recordMetric("sample", 1.0)
        val oldest = h.rum.snapshot().batchIds.single()
        h.rum.recordMetric("sample", 2.0)
        assertEquals(1, h.rum.snapshot().pending)
        assertFalse(h.rum.snapshot().batchIds.contains(oldest))
        assertEquals(1L, h.rum.snapshot().counters.dropped_capacity)
        assertTrue(h.rum.snapshot().queuedBytes <= h.config.maxQueueBytes)
        h.rum.flush()
        assertTrue(h.transport.sent.isEmpty())
        h.time += 1_000
        h.rum.flush() // Age expires even while offline.
        assertEquals(0, h.rum.snapshot().pending)
        assertEquals(DeliveryCounters(attempted = 2, dropped_capacity = 1, dropped_error = 1), h.rum.snapshot().counters)
        h.assertAccounted()
        val tiny = Harness(file("tiny.json"), config = Harness.config().copy(maxQueueBytes = 1))
        tiny.rum.grantConsent(); tiny.rum.recordMetric("sample", 1.0)
        assertEquals(1L, tiny.rum.snapshot().counters.dropped_capacity)
        tiny.assertAccounted()
    }

    @Test fun lateAckAfterWithdrawalCannotDoubleCountOrResurrectAndConcurrentFlushIsSingleFlight() = runBlocking {
        val h = Harness(file())
        h.rum.grantConsent(); h.rum.recordMetric("sample", 1.0); h.network.setOnline(true)
        h.transport.duringSend = { h.rum.withdrawConsent(); h.rum.grantConsent() }
        val a = async { h.rum.flush() }
        val b = async { h.rum.flush() }
        a.await(); b.await()
        assertEquals(1, h.transport.sent.size)
        assertTrue(h.transport.cancelled > 0)
        assertEquals(DeliveryCounters(attempted = 1, dropped_error = 1), h.rum.snapshot().counters)
        assertTrue(FileQueueStore(file()).load().batches.isEmpty())
        h.assertAccounted()
    }

    @Test fun lateAckOfCapacityEvictedBatchIsIgnored() = runBlocking {
        val measure = Harness(file("measure.json"))
        measure.rum.grantConsent(); measure.rum.recordMetric("sample", 1.0)
        val h = Harness(file(), config = Harness.config().copy(maxQueueBytes = measure.rum.snapshot().queuedBytes + 128))
        h.rum.grantConsent(); h.rum.recordMetric("sample", 1.0); h.network.setOnline(true)
        h.transport.duringSend = {
            h.transport.duringSend = null
            h.rum.recordMetric("sample", 2.0)
        }
        h.rum.flush()
        assertEquals(DeliveryCounters(attempted = 2, acked = 1, dropped_capacity = 1), h.rum.snapshot().counters)
        h.assertAccounted()
    }

    @Test fun retryAfterDateAndJitterBackoffHaveKnownBounds() {
        val config = Harness.config()
        val now = 1_700_000_000_000L
        assertEquals(50L, retryDelay(config, Outcome(503), 1, now, 0.0))
        assertEquals(100L, retryDelay(config, Outcome(503), 1, now, 1.0))
        assertEquals(200L, retryDelay(config, Outcome(503), 2, now, 1.0))
        assertEquals(500L, retryDelay(config, Outcome(503), 10, now, 1.0))
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now + 60_000).atZone(ZoneOffset.UTC))
        assertEquals(60_000L, retryDelay(config, Outcome(503, date), 1, now, 0.0))
        assertEquals(50L, retryDelay(config, Outcome(503, "nonsense"), 1, now, 0.0))
    }

    @Test fun diskWriteFailureRetainsPendingAndSuspendsExportWithoutThrowing() = runBlocking {
        val actual = FileQueueStore(file())
        var fail = false
        val store = object : QueueStore {
            override fun load() = actual.load()
            override fun save(ledger: Ledger) { if (fail) throw IOException("disk full"); actual.save(ledger) }
        }
        val h = Harness(file(), store = store)
        h.rum.grantConsent()
        fail = true
        h.rum.recordMetric("sample", 1.0)
        assertFalse(h.rum.snapshot().storageHealthy)
        assertEquals(1, h.rum.snapshot().pending)
        h.rum.recordMetric("sample", 2.0)
        assertEquals(1L, h.rum.snapshot().counters.dropped_error)
        h.network.setOnline(true); h.rum.flush()
        assertTrue(h.transport.sent.isEmpty())
        h.rum.withdrawConsent() // Counts the retained observation even if durable purge fails.
        h.rum.grantConsent()
        assertFalse(h.rum.snapshot().consent)
        assertEquals(2L, h.rum.snapshot().counters.dropped_error)
        h.assertAccounted()
    }

    @Test fun corruptLedgerIsPreservedUntilDenialThenPurgedWithExplicitAccountingUncertainty() {
        file().writeText("corrupt")
        val h = Harness(file())
        h.rum.grantConsent()
        assertEquals("corrupt", file().readText())
        h.rum.withdrawConsent()
        assertFalse(h.rum.snapshot().consent)
        assertTrue(h.rum.snapshot().accountingUncertain)
        assertFalse(file().readText().contains("corrupt"))
        assertTrue(FileQueueStore(file()).load().accountingUncertain)
        assertEquals(0, h.constructions)
        val next = Harness(file())
        next.rum.grantConsent()
        assertFalse(next.rum.snapshot().consent)
        assertTrue(next.rum.snapshot().accountingUncertain)
    }

    @Test fun realHttpExportsTracesAndMetricsWithAuthAndScrubbedOtlpBodies() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(MockResponse().setResponseCode(200))
            val config = Harness.config().copy(endpoint = server.url("/prefix/").toString())
            val rum = Rum(config, FileQueueStore(file()), ManualConnectivity(), automaticFlush = false)
            rum.grantConsent()
            rum.recordHttp("https://user:password@api.getepok.dev/users/42?token=top-secret#fragment")
            rum.recordMetric("sample.trigger", 2.0)
            assertFalse(file().readText().contains("top-secret"))
            rum.flush()
            val traces = server.takeRequest(3, TimeUnit.SECONDS)!!
            val metrics = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("/prefix/v1/traces", traces.path)
            assertEquals("/prefix/v1/metrics", metrics.path)
            for (request in listOf(traces, metrics)) {
                assertEquals("public-key", request.getHeader("X-API-Key"))
                assertEquals("account", request.getHeader("AccountID"))
                assertEquals("project", request.getHeader("ProjectID"))
                assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
            }
            val raw = traces.body.readUtf8()
            assertFalse(raw.contains("password")); assertFalse(raw.contains("top-secret")); assertFalse(raw.contains("fragment"))
            val resource = JSONObject(raw).getJSONArray("resourceSpans").getJSONObject(0)
            val span = resource.getJSONArray("scopeSpans").getJSONObject(0).getJSONArray("spans").getJSONObject(0)
            assertEquals("GET https://api.getepok.dev/users/<redacted>?<redacted>#<redacted>", span.getString("name"))
            val attributes = span.getJSONArray("attributes")
            val url = (0 until attributes.length()).map { attributes.getJSONObject(it) }.single { it.getString("key") == "http.url" }
            assertEquals(span.getString("name").removePrefix("GET "), url.getJSONObject("value").getString("stringValue"))
            assertTrue(span.getString("startTimeUnixNano").length >= 18)
            val metric = JSONObject(metrics.body.readUtf8()).getJSONArray("resourceMetrics").getJSONObject(0)
                .getJSONArray("scopeMetrics").getJSONObject(0).getJSONArray("metrics").getJSONObject(0)
            assertEquals(2.0, metric.getJSONObject("gauge").getJSONArray("dataPoints").getJSONObject(0).getDouble("asDouble"), 0.0)
            assertEquals(DeliveryCounters(attempted = 2, acked = 2), rum.snapshot().counters)
        } finally { server.shutdown() }
    }

    @Test fun realHttpErrorsRetryOnlyEligibleStatusesAndSocketFailureRetainsBatch() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "2"))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(400))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setResponseCode(200))
            var time = 1_700_000_000_000L
            val config = Harness.config().copy(endpoint = server.url("/").toString(), requestTimeoutMs = 1_000)
            val rum = Rum(config, FileQueueStore(file()), ManualConnectivity(), now = { time }, random = { 1.0 }, automaticFlush = false)
            rum.grantConsent(); rum.recordMetric("sample", 1.0)
            val id = rum.snapshot().batchIds.single()
            rum.flush()
            assertEquals(1, rum.snapshot().pending)
            assertEquals(0L, rum.snapshot().counters.acked)
            time += 100; rum.flush()
            assertEquals(1, rum.snapshot().pending)
            time += 1_999; rum.flush()
            assertEquals(2, server.requestCount)
            time++; rum.flush()
            assertEquals(1L, rum.snapshot().counters.acked)
            val retriedBodies = (1..3).map { server.takeRequest(3, TimeUnit.SECONDS)!!.body.readUtf8() }
            assertEquals(1, retriedBodies.distinct().size)
            assertTrue(retriedBodies.all { it.contains(id) })
            rum.recordMetric("sample", 2.0); rum.flush()
            assertEquals(1L, rum.snapshot().counters.dropped_error)
            rum.recordMetric("sample", 3.0); rum.flush()
            assertEquals(1, rum.snapshot().pending)
            assertEquals(1L, rum.snapshot().counters.acked)
            time += 100; rum.flush()
            assertEquals(DeliveryCounters(attempted = 3, acked = 2, dropped_error = 1), rum.snapshot().counters)
        } finally { server.shutdown() }
    }
}
