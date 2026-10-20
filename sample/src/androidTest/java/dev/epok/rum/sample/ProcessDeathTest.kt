package dev.epok.rum.sample

import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Two instrumentation invocations separated by an EXTERNAL am force-stop + launch.
 * The instrumentation itself lives in the target process, so killing it from a single
 * connectedAndroidTest method would abort that runner rather than prove persistence.
 * the device test harness runs both phases and verifies the real collector's received batch ID.
 */
@RunWith(AndroidJUnit4::class)
class ProcessDeathTest {
    private val args get() = InstrumentationRegistry.getArguments()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SampleApplication

    @Test fun prepareForExternalKill() {
        assumeTrue("Run through the device test harness for external process death", args.getString("deathPhase") == "prepare")
        app.configure(requireNotNull(args.getString("collectorEndpoint")), 4319)
        app.setOnline(false)
        app.rum.grantConsent()
        app.enqueue()
        val state = app.rum.snapshot()
        assertTrue(state.storageHealthy)
        assertEquals(1, state.pending)
        assertEquals(1L, state.counters.attempted)
        assertEquals(0L, state.counters.acked)
        app.writeStatus("death-before.json")
        // No close(), withdraw(), in-memory reload, or activity recreation masquerading as process death.
    }

    @Test fun verifyAfterExternalKillAndRelaunch() {
        assumeTrue("Run through the device test harness for external process death", args.getString("deathPhase") == "verify")
        val before = JSONObject(File(app.filesDir, "death-before.json").readText())
        assertNotEquals(before.getInt("pid"), Process.myPid())
        assertFalse(app.rum.snapshot().consent)
        assertFalse(app.rum.snapshot().exporterConstructed)
        assertNull(app.rum.snapshot().sessionId)
        app.rum.grantConsent() // Offline state also survived; inspect recovered state before any send.
        val restored = app.rum.snapshot()
        assertFalse(app.network.isOnline())
        assertEquals(1, restored.pending)
        assertEquals(before.getJSONArray("batchIds").getString(0), restored.batchIds.single())
        assertEquals(1L, restored.counters.attempted)
        assertEquals(0L, restored.counters.acked)
        assertNotEquals(before.getString("sessionId"), restored.sessionId)
        app.writeStatus("death-restored.json")
        app.setOnline(true)
        val end = SystemClock.elapsedRealtime() + 15_000
        while (app.rum.snapshot().counters.acked != 1L && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25)
        val delivered = app.rum.snapshot()
        assertEquals(1L, delivered.counters.acked)
        assertEquals(1L, delivered.counters.attempted)
        assertEquals(0L, delivered.counters.dropped_error)
        assertEquals(0, delivered.pending)
        app.writeStatus("death-after.json")
    }
}
