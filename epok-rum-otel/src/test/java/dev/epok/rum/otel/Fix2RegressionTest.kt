package dev.epok.rum.otel

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Looper
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import okhttp3.Interceptor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Fix2RegressionTest {
    private val app get() = RuntimeEnvironment.getApplication() as Application
    private val prefs get() = app.getSharedPreferences("epok-rum-otel-consent", 0)
    @Before fun reset() { prefs.edit().clear().commit() }
    @Test fun failurePreservesUserGrantAcrossRestart() {
        prefs.edit().putBoolean("granted", true).commit()
        var attempts = 0
        repeat(2) {
            val rum = ConsentController(app, java.util.concurrent.Executor { it.run() }) {
                attempts++; error("injected failure")
            }
            assertEquals(ConsentState.FAILED, rum.state())
            assertTrue("Failure must preserve the user's consent", prefs.getBoolean("granted", false))
        }
        assertEquals(2, attempts)
    }
    @Test fun upstreamStartNeverReachesCollector() {
        val sink = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(PrivateSpans(sink, ExportGate()))).build()
        provider.get("upstream").spanBuilder("AppStart").startSpan().end()
        provider.get("wrapper").spanBuilder("app.start").startSpan().end()
        assertEquals(listOf("app.start"), sink.finishedSpanItems.map { it.name })
        provider.close()
    }
    @Test fun privacyRemovalDoesNotInflateDroppedCount() {
        val sink = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(sink)).build()
        provider.get("test").spanBuilder("test").startSpan().setAllAttributes(
            Attributes.builder().put("network.carrier.name", "private").put("keep", true).build()).end()
        val exported = Privacy.span(sink.finishedSpanItems.single())
        assertEquals(exported.attributes.size(), exported.totalAttributeCount)
        provider.close()
    }
    @Test fun recoveredRecordCountsPreserveRealSdkDrops() {
        val sink = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(sink)).build()
        provider.get("test").spanBuilder("recovered").startSpan().setAttribute("keep", true).end()
        val original = sink.finishedSpanItems.single()
        val recovered = object : io.opentelemetry.sdk.trace.data.SpanData by original {
            override fun getTotalAttributeCount() = original.totalAttributeCount + 5
        }
        val exported = Privacy.span(recovered)
        assertEquals(exported.attributes.size() + 5, exported.totalAttributeCount)
        provider.close()
    }
    @Test fun gateCloseDoesNotAwaitInFlightResponse() {
        val gate = ExportGate(); val entered = CountDownLatch(1); val result = CompletableResultCode()
        val sender = Thread { gate.send { entered.countDown(); result } }.apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val closed = CountDownLatch(1)
        val closer = Thread { gate.close(); closed.countDown() }.apply { start() }
        try { assertTrue("Closing admission must not await the response", closed.await(50, TimeUnit.MILLISECONDS)) }
        finally { result.succeed(); sender.join(2000); closer.join(2000) }
    }
    private fun tracker(): Pair<ColdStart, Activity> {
        val manager = app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(manager).setProcesses(listOf(ActivityManager.RunningAppProcessInfo().apply {
            pid = android.os.Process.myPid(); importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }))
        val tracker = ColdStart(app) { _, _, _ -> fail("Cancelled start must not emit") }
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        tracker.onActivityCreated(activity, null)
        return tracker to activity
    }
    private fun finished(tracker: ColdStart) = ColdStart::class.java.getDeclaredField("finished").apply { isAccessible = true }.getBoolean(tracker)
    @Test fun stoppedBeforeFrameCancelsStart() {
        val (tracker, activity) = tracker()
        tracker.onActivityStopped(activity)
        try { assertTrue(finished(tracker)) } finally { tracker.close() }
    }
    @Test fun abandonedTrampolineExpires() {
        val (tracker, activity) = tracker()
        tracker.onActivityDestroyed(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        try { assertTrue(finished(tracker)) } finally { tracker.close() }
    }
    @Test fun firstFrameHasHardDeadline() {
        val (tracker, _) = tracker()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        try { assertTrue(finished(tracker)) } finally { tracker.close() }
    }
}
