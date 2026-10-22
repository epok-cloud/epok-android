package dev.epok.rum.otel

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor
import java.util.concurrent.TimeUnit

/** Admission is short; response waits occur on the exporting worker, outside the lock. */
internal class ExportGate(@Volatile private var open: Boolean = true) {
    private val calls = mutableSetOf<okhttp3.Call>()
    @Synchronized fun enable() { open = true }
    @Synchronized fun close() {
        open = false
        calls.forEach { it.cancel() }
        calls.clear()
    }
    fun isOpen() = open
    @Synchronized fun enqueue(call: okhttp3.Call, callback: okhttp3.Callback) {
        if (!open) { call.cancel(); callback.onFailure(call, java.io.IOException("Consent withdrawn")); return }
        calls.add(call)
        call.enqueue(callback)
    }
    @Synchronized fun completed(call: okhttp3.Call) { calls.remove(call) }
    fun send(export: () -> CompletableResultCode): CompletableResultCode {
        if (!open) return CompletableResultCode.ofSuccess()
        return export().join(10, TimeUnit.SECONDS)
    }
}

/** Mark direct children before disk serialization, even if they end/export before AppStart. */
internal class StartupParents : SpanProcessor {
    private val suppressed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun key(context: SpanContext) = context.traceId + context.spanId
    override fun onStart(parentContext: Context, span: ReadWriteSpan) {
        if (span.name == "AppStart") suppressed.add(key(span.spanContext))
        if (key(Span.fromContext(parentContext).spanContext) in suppressed) span.setAttribute(CHILD, true)
    }
    override fun isStartRequired() = true
    override fun onEnd(span: ReadableSpan) = Unit
    override fun isEndRequired() = false
    fun remember(spans: Collection<SpanData>) {
        spans.filter { it.name == "AppStart" }.forEach { suppressed.add(key(it.spanContext)) }
    }
    fun repair(span: SpanData): SpanData {
        if (key(span.parentSpanContext) !in suppressed && span.attributes.get(CHILD) != true) return span
        return object : SpanData by span {
            // Kotlin delegation does not forward Java defaults. Disk records reject the
            // legacy library getter, so preserve scope explicitly and adapt the legacy API.
            override fun getInstrumentationScopeInfo() = span.instrumentationScopeInfo
            @Suppress("DEPRECATION")
            override fun getInstrumentationLibraryInfo() = Privacy.libraryInfo(span)
            override fun getTraceId() = span.traceId
            override fun getSpanId() = span.spanId
            // Retain trace/span IDs so descendants continue to resolve. Multiple roots are valid.
            override fun getParentSpanContext() = SpanContext.getInvalid()
            override fun getParentSpanId() = SpanContext.getInvalid().spanId
        }
    }
    companion object { val CHILD: AttributeKey<Boolean> = AttributeKey.booleanKey("epok.internal.start_parent") }
}

internal class PrivateSpans @JvmOverloads constructor(
    private val delegate: SpanExporter,
    private val gate: ExportGate,
    private val parents: StartupParents = StartupParents()
) : SpanExporter {
    override fun export(spans: Collection<SpanData>) = gate.send {
        parents.remember(spans)
        val safe = spans.filter { it.name != "AppStart" }.map { Privacy.span(parents.repair(it)) }
        if (safe.isEmpty()) CompletableResultCode.ofSuccess() else delegate.export(safe)
    }
    override fun flush() = delegate.flush()
    override fun shutdown() = delegate.shutdown()
}
internal class PrivateLogs(private val delegate: LogRecordExporter, private val gate: ExportGate) : LogRecordExporter {
    override fun export(logs: Collection<LogRecordData>) = gate.send { delegate.export(logs.map(Privacy::log)) }
    override fun flush() = delegate.flush()
    override fun shutdown() = delegate.shutdown()
}
