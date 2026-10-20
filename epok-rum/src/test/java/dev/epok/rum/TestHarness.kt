package dev.epok.rum

import org.junit.Assert.assertEquals
import java.io.File
import java.util.ArrayDeque

internal class ScriptedTransport(vararg outcomes: Outcome) : Transport {
    val responses = ArrayDeque(outcomes.toList())
    val sent = mutableListOf<Batch>()
    var duringSend: (() -> Unit)? = null
    var cancelled = 0
    override fun newCall(batch: Batch): DeliveryCall = object : DeliveryCall {
        override fun execute(): Outcome {
            sent += batch.copy()
            duringSend?.invoke()
            return if (responses.isEmpty()) Outcome(200) else responses.removeFirst()
        }
        override fun cancel() { cancelled++ }
    }
}

internal class Harness(
    file: File,
    val transport: ScriptedTransport = ScriptedTransport(),
    val config: RumConfig = config(),
    store: QueueStore = FileQueueStore(file)
) {
    var time = 1_700_000_000_000L
    var constructions = 0
    val network = ManualConnectivity(false)
    val rum = Rum(config, store, network, { constructions++; transport }, { time }, { 1.0 }, false)

    fun assertAccounted() {
        val state = rum.snapshot()
        val c = state.counters
        assertEquals(c.attempted, c.acked + c.dropped_capacity + c.dropped_error + state.pending)
    }

    companion object {
        fun config() = RumConfig("https://collector.example", "public-key", "account", "project", "test",
            setOf("https://api.getepok.dev"), retryBaseMs = 100, maxRetryDelayMs = 500)
    }
}
