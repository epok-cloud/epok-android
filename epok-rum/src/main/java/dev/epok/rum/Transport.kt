package dev.epok.rum

import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

internal data class Outcome(val status: Int, val retryAfter: String? = null)
internal interface DeliveryCall {
    fun execute(): Outcome
    fun cancel()
}
internal interface Transport {
    fun newCall(batch: Batch): DeliveryCall
}

/** Constructed only after grant. No RUM interceptor on this client (no self-instrumentation). */
internal class HttpTransport(private val config: RumConfig) : Transport {
    private val endpoint = config.endpoint.trimEnd('/').toHttpUrl().also {
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null)
    }
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(config.requestTimeoutMs, TimeUnit.MILLISECONDS).build()

    override fun newCall(batch: Batch): DeliveryCall {
        val url = endpoint.newBuilder().addPathSegments("v1/${batch.signal}").build()
        val request = Request.Builder().url(url)
            .header("AccountID", config.accountId).header("ProjectID", config.projectId)
            .header("X-API-Key", config.publicIngestKey)
            .post(batch.body.toRequestBody("application/json".toMediaType())).build()
        return HttpDeliveryCall(client.newCall(request))
    }

    private class HttpDeliveryCall(private val call: Call) : DeliveryCall {
        override fun execute(): Outcome = try {
            call.execute().use { Outcome(it.code, it.header("Retry-After")) }
        } catch (_: Exception) { Outcome(0) }
        override fun cancel() = call.cancel()
    }
}

internal fun retryDelay(config: RumConfig, outcome: Outcome, attempts: Int, now: Long, random: Double): Long {
    val backoff = min(config.maxRetryDelayMs.toDouble(), config.retryBaseMs * Math.pow(2.0, (attempts - 1).toDouble()))
    val jittered = (backoff * (0.5 + 0.5 * random.coerceIn(0.0, 1.0))).toLong()
    val retryAfter = outcome.retryAfter?.trim()?.let { text ->
        try {
            if (text.matches(Regex("[0-9]+(\\.[0-9]+)?")))
                (text.toDouble() * 1000).coerceAtMost(Long.MAX_VALUE / 4.0).toLong()
            else (ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceAtLeast(0)
        } catch (_: Exception) { 0L }
    } ?: 0L
    // Retry-After may exceed the backoff cap. Never send early; age expiry can still drop it.
    return max(jittered, retryAfter)
}
