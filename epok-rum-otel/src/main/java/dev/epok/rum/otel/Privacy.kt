package dev.epok.rum.otel

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.data.EventData
import io.opentelemetry.sdk.trace.data.LinkData
import io.opentelemetry.sdk.trace.data.SpanData
import java.net.URI

internal object Privacy {
    const val STACK_LIMIT = 16 * 1024

    fun scrubUrl(value: String): String = try {
        require(value.none { it <= '\u001f' || it == '\u007f' || it == '\\' })
        val base = value.substringBefore('#').substringBefore('?')
        val uri = URI(base)
        require(uri.scheme in setOf("http", "https") && uri.host != null)
        // Preserve escaped path/port, but never URL credentials.
        base.replaceFirst(Regex("^(https?://)[^/]*@"), "$1")
    } catch (_: Exception) { "<redacted>" }

    private fun capUtf8(value: String): String {
        var bytes = 0
        var end = 0
        while (end < value.length) {
            val code = value.codePointAt(end)
            val size = when { code <= 0x7f -> 1; code <= 0x7ff -> 2; code <= 0xffff -> 3; else -> 4 }
            if (bytes + size > STACK_LIMIT) break
            bytes += size
            end += Character.charCount(code)
        }
        return value.substring(0, end)
    }

    @Suppress("UNCHECKED_CAST")
    fun attributes(input: Attributes): Attributes {
        val out = Attributes.builder()
        input.forEach { key, value ->
            when {
                key.key.startsWith("network.carrier.") || key.key == "app.installation.id" ||
                    key.key == "url.query" || key == StartupParents.CHILD -> Unit
                key.key in setOf("url.full", "http.url") -> out.put(key.key, scrubUrl(value.toString()))
                key.key == "exception.stacktrace" -> out.put(key.key, capUtf8(value.toString()))
                else -> out.put(key as AttributeKey<Any>, value)
            }
        }
        return out.build()
    }

    fun resource(input: Resource): Resource = Resource.create(attributes(input.attributes), input.schemaUrl)
    @Suppress("DEPRECATION")
    fun libraryInfo(input: SpanData): io.opentelemetry.sdk.common.InstrumentationLibraryInfo {
        val scope = input.instrumentationScopeInfo
        return io.opentelemetry.sdk.common.InstrumentationLibraryInfo.create(scope.name, scope.version, scope.schemaUrl)
    }
    fun span(input: SpanData): SpanData = object : SpanData by input {
        override fun getInstrumentationScopeInfo() = input.instrumentationScopeInfo
        @Suppress("DEPRECATION")
        override fun getInstrumentationLibraryInfo() = libraryInfo(input)
        override fun getTraceId() = input.traceId
        override fun getSpanId() = input.spanId
        override fun getParentSpanId() = input.parentSpanId
        override fun getAttributes() = attributes(input.attributes)
        override fun getTotalAttributeCount() = filteredCount(input.totalAttributeCount, input.attributes)
        override fun getResource() = resource(input.resource)
        override fun getEvents() = input.events.map { EventData.create(it.epochNanos, it.name, attributes(it.attributes), filteredCount(it.totalAttributeCount, it.attributes)) }
        override fun getLinks() = input.links.map { LinkData.create(it.spanContext, attributes(it.attributes), filteredCount(it.totalAttributeCount, it.attributes)) }
    }
    fun log(input: LogRecordData): LogRecordData = object : LogRecordData by input {
        override fun getEventName() = input.eventName
        override fun getBodyValue() = input.bodyValue
        override fun getAttributes() = attributes(input.attributes)
        override fun getTotalAttributeCount() = filteredCount(input.totalAttributeCount, input.attributes)
        override fun getResource() = resource(input.resource)
    }
    private fun filteredCount(original: Int, input: Attributes) =
        (original - (input.size() - attributes(input).size())).coerceAtLeast(0)
}
