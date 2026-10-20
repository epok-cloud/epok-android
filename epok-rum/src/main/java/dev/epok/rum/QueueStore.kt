package dev.epok.rum

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class DeliveryCounters(
    var attempted: Long = 0,
    var acked: Long = 0,
    var dropped_capacity: Long = 0,
    var dropped_error: Long = 0
) {
    internal fun json() = JSONObject().put("attempted", attempted).put("acked", acked)
        .put("dropped_capacity", dropped_capacity).put("dropped_error", dropped_error)
}

/** One observation per immutable OTLP batch. Retry metadata is persisted separately. */
internal data class Batch(
    val id: String, val signal: String, val body: String, val createdAt: Long,
    var attempts: Int = 0, var notBefore: Long = 0
) {
    fun json() = JSONObject().put("id", id).put("signal", signal).put("body", body)
        .put("createdAt", createdAt).put("attempts", attempts).put("notBefore", notBefore)
    fun bytes(): Long = json().toString().toByteArray(Charsets.UTF_8).size.toLong()
}

internal data class Ledger(
    val counters: DeliveryCounters = DeliveryCounters(),
    val batches: MutableList<Batch> = mutableListOf(),
    var accountingUncertain: Boolean = false
) {
    fun json() = JSONObject().put("version", 1).put("counters", counters.json())
        .put("accountingUncertain", accountingUncertain)
        .put("batches", JSONArray().also { out -> batches.forEach { out.put(it.json()) } })
}

internal interface QueueStore {
    fun load(): Ledger
    fun save(ledger: Ledger)
}

/** Single SDK owner per storage identity/process. AtomicFile syncs before replacing the file. */
internal class FileQueueStore(file: File) : QueueStore {
    private val file = AtomicFile(file)
    override fun load(): Ledger {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return Ledger()
        val raw = file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        val root = JSONObject(raw)
        require(root.getInt("version") == 1)
        val counts = root.getJSONObject("counters")
        val ledger = Ledger(DeliveryCounters(counts.getLong("attempted"), counts.getLong("acked"),
            counts.getLong("dropped_capacity"), counts.getLong("dropped_error")))
        ledger.accountingUncertain = root.optBoolean("accountingUncertain", false)
        val values = root.getJSONArray("batches")
        val ids = mutableSetOf<String>()
        repeat(values.length()) { i ->
            val b = values.getJSONObject(i)
            val batch = Batch(b.getString("id"), b.getString("signal"), b.getString("body"),
                b.getLong("createdAt"), b.getInt("attempts"), b.getLong("notBefore"))
            require(batch.id.matches(Regex("[0-9a-f]{32}")) && ids.add(batch.id))
            require(batch.signal in setOf("traces", "metrics") && batch.attempts >= 0)
            ledger.batches += batch
        }
        val c = ledger.counters
        require(listOf(c.attempted, c.acked, c.dropped_capacity, c.dropped_error).all { it >= 0 })
        require(c.attempted == c.acked + c.dropped_capacity + c.dropped_error + ledger.batches.size)
        return ledger
    }

    override fun save(ledger: Ledger) {
        check(file.baseFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val bytes = ledger.json().toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try { stream.write(bytes); stream.fd.sync(); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
