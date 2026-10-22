package dev.epok.rum.otel

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.context.Context
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.data.EventData
import io.opentelemetry.sdk.trace.data.LinkData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class RecoveredExportTest {
    private fun deserialize(kind: String, bytes: ByteArray): List<*> {
        val type = Class.forName("io.opentelemetry.contrib.disk.buffering.internal.serialization.deserializers.SignalDeserializer")
        return type.getMethod("deserialize", ByteArray::class.java).invoke(type.getMethod("of$kind").invoke(null), bytes) as List<*>
    }
    private fun recover(kind: String, records: Collection<*>): List<*> {
        val type = Class.forName("io.opentelemetry.contrib.disk.buffering.internal.serialization.serializers.SignalSerializer")
        val writer = type.getMethod("of$kind").invoke(null)
        type.getMethod("initialize", Collection::class.java).invoke(writer, records)
        val buffer = java.io.ByteArrayOutputStream()
        type.getMethod("writeBinaryTo", java.io.OutputStream::class.java).invoke(writer, buffer)
        val size = type.getMethod("getBinarySerializedSize").invoke(writer) as Int
        return deserialize(kind, buffer.toByteArray().takeLast(size).toByteArray())
    }
    private fun child(): SpanData {
        val sink = InMemorySpanExporter.create()
        SdkTracerProvider.builder().addSpanProcessor(StartupParents())
            .addSpanProcessor(SimpleSpanProcessor.create(sink)).build().use { provider ->
                val tracer = provider.tracerBuilder("recovered-scope").setInstrumentationVersion("v1").setSchemaUrl("https://schema.test").build()
                val parent = tracer.spanBuilder("AppStart").startSpan()
                tracer.spanBuilder("Created").setParent(Context.root().with(parent)).addLink(parent.spanContext)
                    .startSpan().addEvent("event", Attributes.of(io.opentelemetry.api.common.AttributeKey.stringKey("keep"), "value")).end()
                val raw = sink.finishedSpanItems.single()
                val scoped = object : SpanData by raw {
                    override fun getInstrumentationScopeInfo() = io.opentelemetry.sdk.common.InstrumentationScopeInfo.builder("recovered-scope")
                        .setVersion("v1").setSchemaUrl("https://schema.test")
                        .setAttributes(Attributes.of(io.opentelemetry.api.common.AttributeKey.stringKey("scope-key"), "scope-value")).build()
                }
                val recovered = recover("Spans", listOf(scoped)).single() as SpanData
                parent.end()
                assertTrue(recovered.javaClass.name.endsWith("SpanDataImpl"))
                return recovered
            }
    }
    @Test fun repairedDiskSpanReachesRealReusableHttpExporter() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            // Intentionally leave memory mode at the SDK's default REUSABLE_DATA.
            OtlpHttpSpanExporter.builder().setEndpoint(server.url("/v1/traces").toString()).build().use { exporter ->
                val result = try { PrivateSpans(exporter, ExportGate()).export(listOf(child())) }
                    catch (error: Throwable) { error.printStackTrace(); throw AssertionError("Recovered export threw", error) }
                assertTrue(result.join(5, TimeUnit.SECONDS).isSuccess)
                val request = server.takeRequest(5, TimeUnit.SECONDS)
                assertNotNull("Real HTTP delivery required", request)
                assertEquals(1, server.requestCount)
                val type = Class.forName("io.opentelemetry.diskbuffering.proto.collector.trace.v1.ExportTraceServiceRequest")
                val adapter = type.getField("ADAPTER").get(null)
                val wire = adapter.javaClass.getMethod("decode", ByteArray::class.java).invoke(adapter, request!!.body.readByteArray())
                fun field(value: Any, name: String) = value.javaClass.getField(name).get(value)!!
                val resource = (field(wire, "resource_spans") as List<*>).single()!!
                val scope = (field(resource, "scope_spans") as List<*>).single()!!
                assertEquals("recovered-scope", field(field(scope, "scope"), "name"))
                assertEquals(1, (field(field(scope, "scope"), "attributes") as List<*>).size)
                val span = (field(scope, "spans") as List<*>).single()!!
                assertEquals(0, (field(span, "parent_span_id") as okio.ByteString).size)
            }
        }
    }
    private fun sweep(type: Class<*>, original: Any, wrapped: Any, changed: Map<String, Any?> = emptyMap()) {
        for (getter in type.methods.filter { it.parameterCount == 0 && (it.name.startsWith("get") || it.name == "hasEnded") }) {
            val actual = try { getter.invoke(wrapped) }
                catch (error: Throwable) { error.printStackTrace(); throw AssertionError("${type.simpleName}.${getter.name} threw", error) }
            val expected = if (changed.containsKey(getter.name)) changed[getter.name] else getter.invoke(original)
            assertEquals(getter.name, expected, actual)
        }
    }
    @Suppress("DEPRECATION")
    @Test fun everyPublicGetterWorksOnEveryRecoveredWrapper() {
        val input = child()
        val scope = input.instrumentationScopeInfo
        // The input's deprecated library getter is deliberately unsupported; wrappers provide
        // its equivalent scope projection so even legacy marshalers have a usable getter.
        val library = io.opentelemetry.sdk.common.InstrumentationLibraryInfo.create(scope.name, scope.version, scope.schemaUrl)
        val repaired = StartupParents().repair(input)
        val changes = mapOf("getInstrumentationLibraryInfo" to library,
            "getParentSpanContext" to io.opentelemetry.api.trace.SpanContext.getInvalid(),
            "getParentSpanId" to io.opentelemetry.api.trace.SpanContext.getInvalid().spanId)
        sweep(SpanData::class.java, input, repaired, changes)
        for (raw in listOf(input, repaired)) {
            val safe = Privacy.span(raw)
            sweep(SpanData::class.java, raw, safe, mapOf("getInstrumentationLibraryInfo" to library,
                "getAttributes" to Privacy.attributes(raw.attributes), "getTotalAttributeCount" to raw.totalAttributeCount - 1,
                "getResource" to Privacy.resource(raw.resource), "getEvents" to safe.events, "getLinks" to safe.links))
            raw.events.zip(safe.events).forEach { (a,b) -> sweep(EventData::class.java, a,b) }
            raw.links.zip(safe.links).forEach { (a,b) -> sweep(LinkData::class.java, a,b) }
        }
        val sink = InMemoryLogRecordExporter.create()
        SdkLoggerProvider.builder().addLogRecordProcessor(SimpleLogRecordProcessor.create(sink)).build().use { provider ->
            provider.get("log-scope").logRecordBuilder().setEventName("app.crash").setBody("message").emit()
            val raw = recover("Logs", sink.finishedLogRecordItems).single() as LogRecordData
            assertTrue(raw.javaClass.name.endsWith("LogRecordDataImpl"))
            sweep(LogRecordData::class.java, raw, Privacy.log(raw))
        }
    }
}
