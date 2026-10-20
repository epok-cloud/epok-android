package dev.epok.rum.sample

import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SampleInvariantTest {
    private lateinit var app: SampleApplication
    private lateinit var activity: ActivityScenario<MainActivity>
    private val collector = MockWebServer()
    private val origin = MockWebServer()

    @Before fun start() {
        val respond = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200).setBody("{}")
        }
        collector.dispatcher = respond
        origin.dispatcher = respond
        collector.start(InetAddress.getByName("127.0.0.1"), 0)
        origin.start(InetAddress.getByName("127.0.0.1"), 0)
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SampleApplication
        app.configure(collector.url("/").toString(), origin.port)
        activity = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun stop() {
        if (::app.isInitialized) app.rum.withdrawConsent()
        if (::activity.isInitialized) activity.close()
        collector.shutdown(); origin.shutdown()
    }

    private fun press(id: Int) { activity.onActivity { it.findViewById<Button>(id).performClick() } }

    @Test fun consentControlsGateCaptureExporterAndPropagationThenPurge() {
        press(R.id.deny_consent)
        press(R.id.call_allowed)
        val deniedCall = origin.takeRequest(5, TimeUnit.SECONDS)!!
        assertNull(deniedCall.getHeader("traceparent"))
        assertNull(deniedCall.getHeader("baggage"))
        assertFalse(app.rum.snapshot().exporterConstructed)
        assertEquals(0L, app.rum.snapshot().counters.attempted)
        assertNull(collector.takeRequest(300, TimeUnit.MILLISECONDS))
        press(R.id.go_offline)
        eventually { !app.network.isOnline() }
        press(R.id.grant_consent)
        eventually { app.rum.snapshot().consent }
        val firstSession = app.rum.snapshot().sessionId
        press(R.id.enqueue)
        eventually { app.rum.snapshot().pending == 1 }
        press(R.id.withdraw_consent)
        eventually { !app.rum.snapshot().consent && app.rum.snapshot().pending == 0 }
        assertEquals(1L, app.rum.snapshot().counters.dropped_error)
        assertNull(app.rum.snapshot().sessionId)
        press(R.id.grant_consent)
        eventually { app.rum.snapshot().consent }
        assertNotEquals(firstSession, app.rum.snapshot().sessionId)
        assertEquals(0, app.rum.snapshot().pending)
        eventually {
            var visible = false
            activity.onActivity { visible = it.findViewById<TextView>(R.id.delivery_status).text.contains("dropped_error") }
            visible
        }
    }

    @Test fun realAllowedAndLookalikeRequestsExposeHeadersAtLocalServer() {
        press(R.id.go_offline)
        eventually { !app.network.isOnline() }
        press(R.id.grant_consent)
        eventually { app.rum.snapshot().consent }
        press(R.id.call_allowed)
        val allowed = origin.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("api.getepok.dev:${origin.port}", allowed.getHeader("Host"))
        assertTrue(allowed.getHeader("traceparent")!!.matches(Regex("00-[0-9a-f]{32}-[0-9a-f]{16}-01")))
        assertEquals("session.id=${app.rum.snapshot().sessionId}", allowed.getHeader("baggage"))
        press(R.id.call_lookalike)
        val denied = origin.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("api.evil-getepok.dev:${origin.port}", denied.getHeader("Host"))
        assertNull(denied.getHeader("traceparent"))
        assertNull(denied.getHeader("baggage"))
        eventually { app.rum.snapshot().pending == 2 }
        assertEquals(2L, app.rum.snapshot().counters.attempted)
    }

    @Test fun offlineToOnlineDrainsRealTracesAndMetricsAndExposesCounters() {
        press(R.id.go_offline)
        eventually { !app.network.isOnline() }
        press(R.id.grant_consent)
        eventually { app.rum.snapshot().consent }
        press(R.id.enqueue); press(R.id.record_metric)
        eventually { app.rum.snapshot().pending == 2 }
        val ids = app.rum.snapshot().batchIds
        assertNull(collector.takeRequest(300, TimeUnit.MILLISECONDS))
        assertEquals(0L, app.rum.snapshot().counters.acked)
        press(R.id.go_online)
        eventually { app.rum.snapshot().counters.acked == 2L }
        assertEquals(0, app.rum.snapshot().pending)
        assertEquals(2L, app.rum.snapshot().counters.attempted)
        val requests = listOf(collector.takeRequest(5, TimeUnit.SECONDS)!!, collector.takeRequest(5, TimeUnit.SECONDS)!!)
        assertEquals(setOf("/v1/traces", "/v1/metrics"), requests.map { it.path }.toSet())
        val payloads = requests.map { it.body.readUtf8() }
        for (id in ids) assertEquals(1, payloads.count { it.contains(id) })
        assertFalse(payloads.any { it.contains("sample-secret") })
        assertTrue(requests.all { it.getHeader("AccountID") == "poc-account" && it.getHeader("ProjectID") == "poc-project" })
    }

    private fun eventually(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        assertTrue("Timed out; ${app.status()}", condition())
    }
}
