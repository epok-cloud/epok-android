package dev.epok.rum.otel

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Value
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PrivacyTest {
    private val unsafe = Attributes.builder().put("url.full", "https://example.com:8443/a%20b?token=SECRET#private")
        .put("http.url", "https://alice:password@example.com/path?token=SECRET#private")
        .put("url.query", "token=SECRET").put("network.carrier.name", "carrier")
        .put("network.carrier.mcc", 123L).put("exception.stacktrace", "😀".repeat(10000))
        .put("http.response.status_code", 200L).build()
    private fun assertSafe(a: Attributes) {
        assertEquals("https://example.com:8443/a%20b", a.get(AttributeKey.stringKey("url.full")))
        assertEquals("https://example.com/path", a.get(AttributeKey.stringKey("http.url")))
        assertNull(a.get(AttributeKey.stringKey("url.query")))
        assertFalse(a.asMap().keys.any { it.key.startsWith("network.carrier.") })
        val stack = a.get(AttributeKey.stringKey("exception.stacktrace"))!!
        assertEquals(16 * 1024, stack.toByteArray().size)
        assertFalse(stack.contains('\ufffd'))
        assertEquals(200L, a.get(AttributeKey.longKey("http.response.status_code")))
    }
    @Test fun urlScrubPreservesSchemeHostPortAndPath() { assertSafe(Privacy.attributes(unsafe)) }
    @Test fun diskRecoveredSpanPreservesScopeWithoutDeprecatedGetter() {
        val sink = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(sink)).build()
        provider.get("recovered-scope").spanBuilder("recovered").startSpan().end()
        val original = sink.finishedSpanItems.single()
        // OTel's disk-buffering SpanDataImpl intentionally rejects the deprecated getter.
        val recovered = object : io.opentelemetry.sdk.trace.data.SpanData by original {
            @Suppress("DEPRECATION")
            override fun getInstrumentationLibraryInfo(): io.opentelemetry.sdk.common.InstrumentationLibraryInfo =
                throw UnsupportedOperationException("disk records use scope info")
            override fun getInstrumentationScopeInfo() = original.instrumentationScopeInfo
        }
        assertEquals("recovered-scope", Privacy.span(recovered).instrumentationScopeInfo.name)
        provider.close()
    }
    @Test fun malformedUrlsFailClosed() {
        listOf("not a url", "https://example.com\\?token=SECRET", "https://example.com/\nsecret", "javascript:secret")
            .forEach { assertEquals("<redacted>", Privacy.scrubUrl(it)) }
    }
    @Test fun spanExporterScrubsAttributesEventsLinksAndResource() {
        val sink = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder().setResource(Resource.create(unsafe.toBuilder().put("app.installation.id", "id").build()))
            .addSpanProcessor(SimpleSpanProcessor.create(PrivateSpans(sink, ExportGate()))).build()
        val parent = provider.get("test").spanBuilder("parent").startSpan()
        val span = provider.get("test").spanBuilder("child").addLink(parent.spanContext, unsafe).startSpan()
        span.setAllAttributes(unsafe).addEvent("exception", unsafe).end()
        val exported = sink.finishedSpanItems.single()
        assertEquals(exported.attributes.size(), exported.totalAttributeCount)
        assertEquals(exported.events.single().attributes.size(), exported.events.single().totalAttributeCount)
        assertEquals(exported.links.single().attributes.size(), exported.links.single().totalAttributeCount)
        assertSafe(exported.attributes); assertSafe(exported.events.single().attributes)
        assertSafe(exported.links.single().attributes); assertSafe(exported.resource.attributes)
        assertNull(exported.resource.attributes.get(AttributeKey.stringKey("app.installation.id")))
        parent.end(); provider.close()
    }
    @Test fun logExporterScrubsEveryRecordAndResource() {
        val sink = InMemoryLogRecordExporter.create()
        val provider = SdkLoggerProvider.builder().setResource(Resource.create(unsafe.toBuilder().put("app.installation.id", "id").build()))
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(PrivateLogs(sink, ExportGate()))).build()
        provider.get("test").logRecordBuilder().setEventName("app.crash").setAllAttributes(unsafe).emit()
        val exported = sink.finishedLogRecordItems.single()
        assertEquals(exported.attributes.size(), exported.totalAttributeCount)
        assertSafe(exported.attributes); assertSafe(exported.resource.attributes)
        assertNull(exported.resource.attributes.get(AttributeKey.stringKey("app.installation.id")))
        assertEquals("app.crash", exported.eventName)
        provider.close()
    }
    @Test fun structuredLogBodiesPreserveMapAndArrayValues() {
        val sink = InMemoryLogRecordExporter.create()
        val provider = SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(PrivateLogs(sink, ExportGate()))).build()
        try {
            val array = Value.of(listOf(Value.of("message"), Value.of(42L), Value.of(true)))
            val map = Value.of(mapOf("items" to array, "nested" to Value.of(mapOf("ok" to Value.of(true)))))
            for (body in listOf(array, map)) {
                provider.get("structured").logRecordBuilder().setBody(body).setAllAttributes(unsafe).emit()
                val exported = sink.finishedLogRecordItems.last()
                assertSafe(exported.attributes)
                assertEquals(body.type, exported.bodyValue!!.type)
                assertEquals(body, exported.bodyValue)
            }
        } finally { provider.close() }
    }
    @Test fun closedGateDropsSpansAndLogsWithoutCallingDelegate() {
        val gate = ExportGate(); gate.close()
        val spans = InMemorySpanExporter.create(); val logs = InMemoryLogRecordExporter.create()
        val traces = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(PrivateSpans(spans, gate))).build()
        val logger = SdkLoggerProvider.builder().addLogRecordProcessor(SimpleLogRecordProcessor.create(PrivateLogs(logs, gate))).build()
        traces.get("test").spanBuilder("after-withdraw").startSpan().end()
        logger.get("test").logRecordBuilder().setBody("after-withdraw").emit()
        assertTrue(spans.finishedSpanItems.isEmpty()); assertTrue(logs.finishedLogRecordItems.isEmpty())
        traces.close(); logger.close()
    }
    @Test fun realLimitDropsRemainVisibleOnLogsEventsAndLinks() {
        val attributes = Attributes.builder().put("keep", true).put("network.carrier.name", "private").build()
        val spanSink = InMemorySpanExporter.create()
        val traces = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanSink)).build()
        val span = traces.get("test").spanBuilder("limited").startSpan()
        span.end()
        val raw = spanSink.finishedSpanItems.single()
        val limited = object : io.opentelemetry.sdk.trace.data.SpanData by raw {
            override fun getEvents() = listOf(io.opentelemetry.sdk.trace.data.EventData.create(1, "event", attributes, 7))
            override fun getLinks() = listOf(io.opentelemetry.sdk.trace.data.LinkData.create(span.spanContext, attributes, 7))
        }
        val safe = Privacy.span(limited)
        assertEquals(5, safe.events.single().droppedAttributesCount)
        assertEquals(5, safe.links.single().totalAttributeCount - safe.links.single().attributes.size())
        val sink = InMemoryLogRecordExporter.create()
        val logger = SdkLoggerProvider.builder().addLogRecordProcessor(SimpleLogRecordProcessor.create(sink)).build()
        logger.get("test").logRecordBuilder().setAllAttributes(attributes).emit()
        val original = sink.finishedLogRecordItems.single()
        val log = object : io.opentelemetry.sdk.logs.data.LogRecordData by original {
            override fun getTotalAttributeCount() = 7
        }
        assertEquals(5, Privacy.log(log).totalAttributeCount - Privacy.log(log).attributes.size())
        val invalidCount = object : io.opentelemetry.sdk.logs.data.LogRecordData by original {
            override fun getTotalAttributeCount() = 0
        }
        assertEquals(0, Privacy.log(invalidCount).totalAttributeCount)
        traces.close(); logger.close()
    }
    @Test fun sdkOverwriteCountIsNotSilentlyErased() {
        val sink = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(sink)).build()
        // SDK 1.65 counts replacements, not just capacity drops, in totalAddedValues.
        provider.get("test").spanBuilder("repeat").startSpan()
            .setAttribute("app.screen.name", "MainActivity")
            .setAttribute("app.screen.name", "MainActivity").end()
        val raw = sink.finishedSpanItems.single()
        assertEquals(1, raw.attributes.size())
        assertEquals(2, raw.totalAttributeCount)
        assertEquals(2, Privacy.span(raw).totalAttributeCount)
        provider.close()
    }
}
