package dev.epok.rum

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class RumConfig(
    val endpoint: String,
    val publicIngestKey: String,
    val accountId: String,
    val projectId: String,
    val serviceName: String,
    val allowedOrigins: Set<String> = emptySet(),
    val maxQueueBytes: Long = 256 * 1024,
    val maxQueueAgeMs: Long = 24 * 60 * 60 * 1000,
    val maxAttempts: Int = 3,
    val retryBaseMs: Long = 500,
    val maxRetryDelayMs: Long = 30_000,
    val requestTimeoutMs: Long = 10_000,
    val flushIntervalMs: Long = 1_000,
    val sessionInactivityMs: Long = 30 * 60 * 1000,
    val sessionMaxDurationMs: Long = 4 * 60 * 60 * 1000
)

data class RumSnapshot(
    val consent: Boolean, val sessionId: String?, val counters: DeliveryCounters,
    val pending: Int, val queuedBytes: Long, val batchIds: List<String>,
    val lastDecision: String, val storageHealthy: Boolean, val accountingUncertain: Boolean,
    val exporterConstructed: Boolean
) {
    fun json(): JSONObject = counters.json().put("consent", consent).put("sessionId", sessionId ?: JSONObject.NULL)
        .put("pending", pending).put("queuedBytes", queuedBytes).put("batchIds", org.json.JSONArray(batchIds))
        .put("lastDecision", lastDecision).put("storageHealthy", storageHealthy)
        .put("accountingUncertain", accountingUncertain).put("exporterConstructed", exporterConstructed)
}

/**
 * Small durable POC: one observation per batch, one writer, one in-flight export.
 * Admission and ledger updates synchronously fsync small files. Hosts should call explicit
 * record methods off their UI thread. Interceptor capture already runs on an OkHttp thread.
 * A new runtime is denied even when an old durable queue exists; only explicit grant loads it.
 */
class Rum internal constructor(
    private val config: RumConfig,
    private val store: QueueStore,
    private val connectivity: Connectivity,
    private val transportFactory: () -> Transport = { HttpTransport(config) },
    private val now: () -> Long = System::currentTimeMillis,
    private val random: () -> Double = Math::random,
    private val automaticFlush: Boolean = true
) {
    private val lock = Any()
    private val flushing = Mutex()
    private var ledger = Ledger()
    private var loaded = false
    private var granted = false
    private var epoch = 0L
    private var session: String? = null
    private var sessionStarted = 0L
    private var sessionSeen = 0L
    private var transport: Transport? = null
    private var scope: CoroutineScope? = null
    private var active: Pair<String, DeliveryCall>? = null
    private var storageHealthy = true
    private var accountingUncertain = false
    private var decision = "denied_default"

    companion object {
        fun create(context: Context, config: RumConfig, connectivity: Connectivity = AndroidConnectivity(context)): Rum {
            // Identity isolates endpoint, project, service, and key rotations; credentials aren't persisted.
            val identity = listOf(config.endpoint, config.accountId, config.projectId, config.serviceName, config.publicIngestKey)
                .joinToString("\u0000")
            val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            return Rum(config, FileQueueStore(File(context.noBackupFilesDir, "epok-rum/$hash.json")), connectivity)
        }
    }

    fun grantConsent() = synchronized(lock) {
        try {
            if (granted || !storageHealthy) return@synchronized
            require(config.maxQueueBytes > 0 && config.maxQueueAgeMs > 0 && config.maxAttempts in 1..10)
            require(config.retryBaseMs > 0 && config.maxRetryDelayMs > 0 && config.requestTimeoutMs > 0 && config.flushIntervalMs > 0)
            require(config.sessionInactivityMs > 0 && config.sessionMaxDurationMs > 0)
            if (!load() || !storageHealthy) return@synchronized
            trim()
            if (!persist()) return@synchronized
            // No exporter construction, timer, listener, session, or HTTP before this explicit call.
            transport = transportFactory()
            touchSession()
            epoch++
            granted = true
            decision = "granted"
            if (automaticFlush) {
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try { connectivity.start { wake() } } catch (_: Exception) { decision = "connectivity_listener_error" }
                scope?.launch {
                    while (isActive) { flush(); delay(config.flushIntervalMs) }
                }
            }
        } catch (_: Exception) {
            stopRuntime()
            decision = "grant_error"
        }
    }

    fun denyConsent() = withdrawConsent()

    fun withdrawConsent() = synchronized(lock) {
        // Close the gate and cancel the call before any disk work. Late responses cannot resurrect data.
        stopRuntime()
        val readable = load()
        ledger.counters.dropped_error += ledger.batches.size
        ledger.batches.clear()
        // An unreadable ledger cannot reveal its lost observation count. Purge its bytes on
        // explicit denial anyway and persist the uncertainty instead of inventing a zero loss.
        if (!readable) ledger.accountingUncertain = true
        decision = if (readable) "withdrawn_purged" else "withdrawn_purged_accounting_uncertain"
        if (persist()) loaded = true
    }

    /** Release runtime resources while retaining the durable queue. This does NOT withdraw consent. */
    fun close() = synchronized(lock) { stopRuntime(); decision = "closed_durable_queue_retained" }

    private fun stopRuntime() {
        granted = false
        epoch++
        session = null
        try { active?.second?.cancel() } catch (_: Exception) { /* pending remains accounted */ }
        active = null
        try { scope?.cancel() } catch (_: Exception) { /* gate is already closed */ }
        scope = null
        try { connectivity.stop() } catch (_: Exception) { /* callbacks still see a closed gate */ }
        transport = null
    }

    private fun load(): Boolean {
        if (loaded) return true
        return try {
            ledger = store.load()
            loaded = true
            accountingUncertain = ledger.accountingUncertain
            if (accountingUncertain) { storageHealthy = false; decision = "accounting_uncertain_export_suspended" }
            true
        }
        catch (_: Exception) {
            storageHealthy = false
            accountingUncertain = true
            decision = "storage_read_error_preserved"
            false
        }
    }

    private fun persist(): Boolean = try { store.save(ledger); true }
    catch (_: Exception) {
        // Retain every pending observation in memory and stop exporting. Do not pretend
        // the queue was made durable or silently turn a retained observation into a loss.
        storageHealthy = false
        decision = "storage_write_error_export_suspended"
        false
    }

    private fun touchSession(): String {
        val time = now()
        if (session == null || time - sessionSeen > config.sessionInactivityMs || time - sessionStarted > config.sessionMaxDurationMs) {
            session = Ids.hex(16)
            sessionStarted = time
        }
        sessionSeen = time
        return session!!
    }

    fun snapshot(): RumSnapshot = synchronized(lock) {
        RumSnapshot(granted, session, ledger.counters.copy(), ledger.batches.size,
            ledger.batches.sumOf { it.bytes() }, ledger.batches.map { it.id }, decision,
            storageHealthy, accountingUncertain, transport != null)
    }

    private fun drop(batch: Batch, capacity: Boolean) {
        if (!ledger.batches.remove(batch)) return
        if (capacity) ledger.counters.dropped_capacity++ else ledger.counters.dropped_error++
        if (active?.first == batch.id) {
            try { active?.second?.cancel() } catch (_: Exception) { /* removed ID fences late ack */ }
        }
        decision = if (capacity) "dropped_capacity" else "dropped_error"
    }

    private fun trim(): Boolean {
        val before = ledger.batches.size
        val time = now()
        ledger.batches.toList().filter { time - it.createdAt >= config.maxQueueAgeMs }.forEach { drop(it, false) }
        var bytes = ledger.batches.sumOf { it.bytes() }
        while (bytes > config.maxQueueBytes && ledger.batches.isNotEmpty()) {
            val oldest = ledger.batches.first()
            bytes -= oldest.bytes()
            drop(oldest, true)
        }
        return ledger.batches.size != before
    }

    private fun admit(signal: String, sessionId: String, event: () -> JSONObject) {
        ledger.counters.attempted++
        try {
            if (!storageHealthy) {
                ledger.counters.dropped_error++
                decision = "dropped_error_storage_unavailable"
                return
            }
            val id = Ids.hex(16)
            val body = Otlp.envelope(signal, event(), id, sessionId, config, ledger.counters)
            ledger.batches += Batch(id, signal, body, now())
            decision = "queued"
            trim()
        } catch (_: Exception) { ledger.counters.dropped_error++; decision = "dropped_error_construction" }
        persist()
    }

    fun recordHttp(url: String, method: String = "GET", status: Int = 200) = synchronized(lock) {
        if (!granted) return@synchronized
        try {
            val time = now()
            val sid = touchSession()
            admit("traces", sid) { Otlp.span(url, method, TraceContext(Ids.hex(16), Ids.hex(8)), time, time, status) }
        } catch (_: Exception) { constructionLoss() }
    }

    fun recordMetric(name: String, value: Double) = synchronized(lock) {
        if (!granted) return@synchronized
        try { admit("metrics", touchSession()) { Otlp.metric(name, value, now()) } }
        catch (_: Exception) { constructionLoss() }
    }

    private fun constructionLoss() {
        ledger.counters.attempted++
        ledger.counters.dropped_error++
        decision = "dropped_error_construction"
        persist()
    }

    internal data class Capture(val epoch: Long, val session: String, val context: TraceContext, val start: Long)
    internal data class Prepared(val request: Request, val capture: Capture?)
    internal fun prepare(request: Request): Prepared = synchronized(lock) {
        try {
            if (!granted) return@synchronized Prepared(Propagation.stripOwned(request), null)
            val sid = touchSession()
            val permitted = Propagation.permits(request.url, config.allowedOrigins)
            val base = if (permitted) request else Propagation.stripOwned(request)
            val context = TraceContext.parse(base.header("traceparent")) ?: TraceContext(Ids.hex(16), Ids.hex(8))
            val outgoing = if (permitted) Propagation.inject(base, sid, context) else base
            decision = if (permitted) "propagation_allowed" else "propagation_denied_origin"
            Prepared(outgoing, Capture(epoch, sid, context, now()))
        } catch (_: Exception) {
            if (granted) constructionLoss()
            Prepared(Propagation.stripOwned(request), null)
        }
    }

    private fun finish(prepared: Prepared, status: Int) = synchronized(lock) {
        val capture = prepared.capture ?: return@synchronized
        if (!granted || epoch != capture.epoch) return@synchronized
        admit("traces", capture.session) {
            Otlp.span(prepared.request.url.toString(), prepared.request.method, capture.context, capture.start, now(), status)
        }
    }

    /** Install with addNetworkInterceptor: each redirect is checked against exact origin again. */
    fun networkInterceptor(): Interceptor = Interceptor { chain ->
        val prepared = prepare(chain.request())
        try {
            chain.proceed(prepared.request).also { finish(prepared, it.code) }
        } catch (error: Exception) {
            finish(prepared, 0)
            throw error // Host request failures retain normal OkHttp semantics.
        }
    }

    private fun wake() {
        synchronized(lock) { if (granted && automaticFlush) scope?.launch { flush() } }
    }

    private data class Work(val batchId: String, val epoch: Long, val call: DeliveryCall?)

    /** Await status and persist the decision. A retry is a future eligible pass, never a busy loop. */
    suspend fun flush() {
        try {
            withContext(Dispatchers.IO) {
                flushing.withLock {
                    val visited = mutableSetOf<String>()
                    while (true) {
                        val work = synchronized(lock) {
                            if (!granted || !storageHealthy) return@synchronized null
                            if (trim() && !persist()) return@synchronized null
                            if (!connectivity.isOnline()) return@synchronized null
                            val batch = ledger.batches.firstOrNull { it.id !in visited && it.notBefore <= now() }
                                ?: return@synchronized null
                            visited += batch.id
                            if (batch.attempts >= config.maxAttempts) {
                                drop(batch, false); persist()
                                return@synchronized Work(batch.id, epoch, null)
                            }
                            batch.attempts++
                            // Count attempts across process death, including a process killed mid-request.
                            trim()
                            if (!persist()) return@synchronized null
                            if (batch !in ledger.batches) return@synchronized Work(batch.id, epoch, null)
                            val call = try { transport?.newCall(batch) } catch (_: Exception) { null }
                            active = call?.let { batch.id to it }
                            Work(batch.id, epoch, call)
                        } ?: break
                        val outcome = try { work.call?.execute() ?: Outcome(0) } catch (_: Exception) { Outcome(0) }
                        synchronized(lock) {
                            if (active?.first == work.batchId) active = null
                            if (!granted || work.epoch != epoch) return@synchronized
                            val batch = ledger.batches.firstOrNull { it.id == work.batchId } ?: return@synchronized
                            when {
                                outcome.status in 200..299 -> {
                                    ledger.batches.remove(batch)
                                    ledger.counters.acked++
                                    decision = "acked"
                                }
                                (outcome.status != 0 && outcome.status != 429 && outcome.status !in 500..599) || batch.attempts >= config.maxAttempts -> drop(batch, false)
                                else -> {
                                    batch.notBefore = now() + retryDelay(config, outcome, batch.attempts, now(), random())
                                    decision = "retry_scheduled"
                                }
                            }
                            trim()
                            persist()
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // No observation is released by a scheduling/connectivity error; the ledger still owns it.
            synchronized(lock) { if (granted) decision = "flush_error_pending_retained" }
        }
    }
}
