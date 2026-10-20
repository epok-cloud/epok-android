package dev.epok.rum

import org.json.JSONArray
import org.json.JSONObject

internal object Otlp {
    private fun attrs(values: Map<String, Any>): JSONArray = JSONArray().also { array ->
        values.forEach { (key, value) ->
            val typed = JSONObject()
            when (value) {
                is Boolean -> typed.put("boolValue", value)
                is Int, is Long -> typed.put("intValue", value.toString())
                is Number -> typed.put("doubleValue", value.toDouble())
                else -> typed.put("stringValue", value.toString())
            }
            array.put(JSONObject().put("key", key).put("value", typed))
        }
    }

    fun span(url: String, method: String, context: TraceContext, startMs: Long, endMs: Long, status: Int): JSONObject {
        val safe = Privacy.scrubUrl(url)
        val verb = method.takeIf { it in setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "CONNECT", "TRACE") } ?: "HTTP"
        return JSONObject().put("traceId", context.traceId).put("spanId", context.spanId)
            .put("name", "$verb $safe").put("kind", 3)
            .put("startTimeUnixNano", (startMs * 1_000_000).toString())
            .put("endTimeUnixNano", (endMs.coerceAtLeast(startMs) * 1_000_000).toString())
            .put("status", JSONObject().put("code", if (status == 0 || status >= 400) 2 else 1))
            .put("attributes", attrs(mapOf("http.url" to safe, "http.method" to verb, "http.status_code" to status)))
    }

    fun metric(name: String, value: Double, now: Long): JSONObject {
        require(name.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,63}")) && value.isFinite())
        return JSONObject().put("name", name).put("unit", "1").put("gauge", JSONObject().put("dataPoints",
            JSONArray().put(JSONObject().put("timeUnixNano", (now * 1_000_000).toString()).put("asDouble", value))))
    }

    fun envelope(signal: String, event: JSONObject, id: String, session: String, config: RumConfig, counters: DeliveryCounters): String {
        val resource = JSONObject().put("attributes", attrs(mapOf(
            "service.name" to config.serviceName, "telemetry.sdk.name" to "epok-rum-android",
            "telemetry.sdk.language" to "kotlin", "rum" to true, "session.id" to session, "rum.batch.id" to id,
            "rum.delivery.attempted" to counters.attempted, "rum.delivery.acked" to counters.acked,
            "rum.delivery.dropped_capacity" to counters.dropped_capacity, "rum.delivery.dropped_error" to counters.dropped_error
        )))
        val spans = signal == "traces"
        val scope = JSONObject().put("scope", JSONObject().put("name", "epok-rum-android"))
            .put(if (spans) "spans" else "metrics", JSONArray().put(event))
        return JSONObject().put(if (spans) "resourceSpans" else "resourceMetrics", JSONArray().put(
            JSONObject().put("resource", resource).put(if (spans) "scopeSpans" else "scopeMetrics", JSONArray().put(scope))
        )).toString()
    }
}
