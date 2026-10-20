package dev.epok.rum

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.security.SecureRandom

internal object Ids {
    private val random = SecureRandom()
    fun hex(bytes: Int): String {
        val data = ByteArray(bytes).also(random::nextBytes)
        if (data.all { it == 0.toByte() }) data[data.lastIndex] = 1
        return data.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

data class TraceContext(val traceId: String, val spanId: String, val flags: String = "01") {
    fun header(): String = "00-$traceId-$spanId-$flags"
    companion object {
        fun parse(value: String?): TraceContext? {
            val m = value?.let { Regex("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})(-[^\\s,]*)?$").matchEntire(it) }
                ?: return null
            val (version, trace, span, flags, extension) = m.destructured
            if (version == "ff" || (version == "00" && extension.isNotEmpty()) ||
                trace.all { it == '0' } || span.all { it == '0' }) return null
            return TraceContext(trace, span, flags)
        }
    }
}

internal data class Origin(val scheme: String, val host: String, val port: Int) {
    companion object {
        fun of(url: HttpUrl): Origin? = if (url.username.isEmpty() && url.password.isEmpty())
            Origin(url.scheme, url.host, url.port) else null
        fun allowed(value: String): Origin? {
            if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) return null
            if (value.any { it <= ' ' || it == '\\' }) return null
            val url = value.toHttpUrlOrNull() ?: return null
            if (url.encodedPath != "/" || url.query != null || url.fragment != null) return null
            return of(url)
        }
    }
}

/** Only SDK-owned additions are removed on a redirected or consent-revoked request. */
internal data class OwnedHeaders(val original: Map<String, String?>, val added: Map<String, String?>)
internal object Propagation {
    fun permits(url: HttpUrl, allowed: Set<String>): Boolean {
        val origin = Origin.of(url) ?: return false
        return allowed.any { Origin.allowed(it) == origin }
    }

    fun stripOwned(request: Request): Request {
        val owned = request.tag(OwnedHeaders::class.java) ?: return request
        val builder = request.newBuilder().tag(OwnedHeaders::class.java, null)
        owned.added.forEach { (key, value) ->
            if (request.header(key) == value) {
                val original = owned.original[key]
                if (original == null) builder.removeHeader(key) else builder.header(key, original)
            }
        }
        return builder.build()
    }

    fun inject(request: Request, session: String, context: TraceContext): Request {
        val inbound = TraceContext.parse(request.header("traceparent"))
        val builder = request.newBuilder()
        if (inbound == null) builder.header("traceparent", context.header())
        if (inbound == null || !validTracestate(request.header("tracestate"))) builder.removeHeader("tracestate")
        val baggage = (request.header("baggage") ?: "").split(',')
            .filter { it.isNotBlank() && !Regex("^(session\\.id|user\\.id)\\s*=").containsMatchIn(it.trim()) }
            .plus("session.id=$session").joinToString(",")
        if (baggage.length <= 8192) builder.header("baggage", baggage) else builder.removeHeader("baggage")
        val injected = builder.build()
        val keys = listOf("traceparent", "tracestate", "baggage")
        val prior = request.tag(OwnedHeaders::class.java)
        return injected.newBuilder().tag(OwnedHeaders::class.java, OwnedHeaders(
            prior?.original ?: keys.associateWith { request.header(it) },
            keys.associateWith { injected.header(it) }
        )).build()
    }

    private fun validTracestate(value: String?): Boolean {
        if (value.isNullOrEmpty() || value.length > 512) return false
        val members = value.split(',')
        if (members.size > 32) return false
        val seen = mutableSetOf<String>()
        return members.all {
            val pair = it.trim().split('=')
            pair.size == 2 && seen.add(pair[0]) &&
                pair[0].matches(Regex("(?:[a-z][a-z0-9_*/-]{0,255}|[a-z0-9][a-z0-9_*/-]{0,240}@[a-z][a-z0-9_*/-]{0,13})")) &&
                pair[1].matches(Regex("[\\x20-\\x2b\\x2d-\\x3c\\x3e-\\x7e]{0,255}[\\x21-\\x2b\\x2d-\\x3c\\x3e-\\x7e]"))
        }
    }
}
