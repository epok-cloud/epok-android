package dev.epok.rum.otel

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.pm.ApplicationInfo
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
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
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Fix3RegressionTest {
    private val app get() = RuntimeEnvironment.getApplication() as Application
    private val prefs get() = app.getSharedPreferences("epok-rum-otel-consent", 0)
    private val direct = Executor { it.run() }
    @Before fun reset() {
        prefs.edit().clear().commit()
        ActivityHistory.created = false
        val manager = app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(manager).setProcesses(listOf(ActivityManager.RunningAppProcessInfo().apply {
            pid = android.os.Process.myPid(); importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }))
    }
    private fun healthy() = object : RumRuntime {
        override fun shutdown() {}
        override fun connectionInterceptor() = Interceptor { it.proceed(it.request()) }
        override fun networkInterceptor() = connectionInterceptor()
    }
    @Test fun startAfterFirstActivityDoesNotEmit() {
        Robolectric.buildContentProvider(ActivityHistory::class.java).create()
        val first = Robolectric.buildActivity(Activity::class.java).create()
        assertTrue(ActivityHistory.created)
        val tracker = ColdStart(app) { _, _, _ -> fail("Late start must not emit") }
        val next = Robolectric.buildActivity(Activity::class.java).get()
        tracker.onActivityCreated(next, null)
        val windows = ColdStart::class.java.getDeclaredField("windows").apply { isAccessible = true }.get(tracker) as Map<*, *>
        try { assertTrue(windows.isEmpty()) }
        finally { tracker.close(); first.destroy(); ActivityHistory.created = false }
    }
    @Test fun markedDiskChildIsRepairedWithoutParentRecordOrLiveRegistry() {
        val raw = InMemorySpanExporter.create()
        val parents = StartupParents()
        val provider = SdkTracerProvider.builder().addSpanProcessor(parents)
            .addSpanProcessor(SimpleSpanProcessor.create(raw)).build()
        val parent = provider.get("upstream").spanBuilder("AppStart").startSpan()
        provider.get("upstream").spanBuilder("Created").setParent(Context.root().with(parent)).startSpan().end()
        // Use the pinned disk writer/reader, including its length-delimited record format.
        val serializerType = Class.forName("io.opentelemetry.contrib.disk.buffering.internal.serialization.serializers.SignalSerializer")
        val serializer = serializerType.getMethod("ofSpans").invoke(null)
        serializerType.getMethod("initialize", Collection::class.java).invoke(serializer, raw.finishedSpanItems)
        val buffer = java.io.ByteArrayOutputStream()
        serializerType.getMethod("writeBinaryTo", java.io.OutputStream::class.java).invoke(serializer, buffer)
        val size = serializerType.getMethod("getBinarySerializedSize").invoke(serializer) as Int
        val bytes = buffer.toByteArray()
        val deserializerType = Class.forName("io.opentelemetry.contrib.disk.buffering.internal.serialization.deserializers.SignalDeserializer")
        val deserializer = deserializerType.getMethod("ofSpans").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val recovered = (deserializerType.getMethod("deserialize", ByteArray::class.java)
            .invoke(deserializer, bytes.copyOfRange(bytes.size - size, bytes.size)) as List<SpanData>).single()
        val sink = InMemorySpanExporter.create()
        PrivateSpans(sink, ExportGate(), StartupParents()).export(listOf(recovered))
        val exported = sink.finishedSpanItems.single()
        assertFalse(exported.parentSpanContext.isValid)
        assertEquals(io.opentelemetry.api.trace.SpanContext.getInvalid().spanId, exported.parentSpanId)
        assertNull(exported.attributes.get(StartupParents.CHILD))
        parent.end(); provider.close()
    }
    @Test fun completedInitDoesNotCountNormalProcessDeathAsFailure() {
        prefs.edit().putBoolean("granted", true).commit()
        val first = ConsentController(app, direct, direct, { 1000L }, 7) { healthy() }
        assertEquals(ConsentState.GRANTED, first.state())
        val second = ConsentController(app, direct, direct, { 1001L }, 7) { healthy() }
        assertEquals(ConsentState.GRANTED, second.state())
        assertFalse(prefs.contains("init_failures")); assertFalse(prefs.contains("init_in_progress"))
        first.withdraw(); second.withdraw()
    }
    @Test fun versionChangeDiscardsOldCrashMarkerAndCap() {
        prefs.edit().putBoolean("granted", true).putInt("init_failures", 5).putLong("failure_version", 6)
            .putBoolean("init_in_progress", true).putLong("init_version", 6).commit()
        val rum = ConsentController(app, direct, direct, { 1000L }, 7) { healthy() }
        assertEquals(ConsentState.GRANTED, rum.state())
        assertFalse(prefs.contains("init_failures")); assertFalse(prefs.contains("init_in_progress")); rum.withdraw()
    }
    @Test fun recoveredChildrenOfFilteredStartAreRoots() {
        val raw = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(raw)).build()
        val tracer = provider.get("upstream")
        val parent = tracer.spanBuilder("AppStart").startSpan()
        tracer.spanBuilder("Created").setParent(Context.root().with(parent)).startSpan().end()
        parent.end()
        val sink = InMemorySpanExporter.create()
        // Recovered batch can contain children before the dropped parent.
        PrivateSpans(sink, ExportGate()).export(raw.finishedSpanItems)
        val child = sink.finishedSpanItems.single()
        assertFalse("Filtered AppStart must not leave an unknown parent", child.parentSpanContext.isValid)
        provider.close()
    }
    @Test fun crashMarkerIsCountedAndBackoffAppliedOnRestart() {
        prefs.edit().putBoolean("granted", true).putInt("init_failures", 1)
            .putLong("failure_version", 7).putBoolean("init_in_progress", true)
            .putLong("init_version", 7).commit()
        var attempts = 0
        val rum = ConsentController(app, direct, direct, { 1000L }, 7) { attempts++; healthy() }
        assertEquals("Previous uncompleted init is a second failure", 2, prefs.getInt("init_failures", 0))
        assertEquals(0, attempts)
        assertEquals(ConsentState.FAILED, rum.state())
        assertFalse(prefs.getBoolean("init_in_progress", false))
        assertTrue(prefs.getBoolean("granted", false))
    }
    @Test fun markerIsDurableBeforeFactoryAndClearedOnSuccess() {
        var markerAtFactory = false
        prefs.edit().putBoolean("granted", true).commit()
        val rum = ConsentController(app, direct, direct, { 1000L }, 7) {
            markerAtFactory = prefs.getBoolean("init_in_progress", false) && prefs.getLong("init_version", -1) == 7L
            healthy()
        }
        assertTrue("Factory must be preceded by a committed marker", markerAtFactory)
        assertEquals(ConsentState.GRANTED, rum.state())
        assertFalse(prefs.contains("init_in_progress"))
        rum.withdraw()
    }
    @Test fun withdrawPreservesFailureBackoffButClearsMarker() {
        prefs.edit().putBoolean("granted", true).putInt("init_failures", 2)
            .putLong("last_failure_at", 1000).putLong("failure_version", 7).commit()
        var attempts = 0
        val rum = ConsentController(app, direct, direct, { 1000L }, 7) { attempts++; healthy() }
        prefs.edit().putBoolean("init_in_progress", true).putLong("init_version", 7).commit()
        rum.withdraw(); rum.grant()
        assertEquals(2, prefs.getInt("init_failures", 0))
        assertFalse(prefs.contains("init_in_progress"))
        assertEquals(0, attempts)
        assertEquals(ConsentState.FAILED, rum.state())
    }
    @Test fun futureFailureTimeIsClamped() {
        var now = 1000L
        prefs.edit().putBoolean("granted", true).putInt("init_failures", 2)
            .putLong("last_failure_at", Long.MAX_VALUE).putLong("failure_version", 7).commit()
        val rum = ConsentController(app, direct, direct, { now }, 7) { healthy() }
        assertEquals(now, prefs.getLong("last_failure_at", -1))
        now += 3_600_000
        rum.grant(); assertEquals(ConsentState.GRANTED, rum.state()); rum.withdraw()
    }
    @Test fun configurationChangeBeforeFrameFollowsRecreatedActivity() {
        val tracker = ColdStart(app) { _, _, _ -> }
        val first = Robolectric.buildActivity(Activity::class.java).get()
        org.robolectric.util.ReflectionHelpers.setField(first, "mChangingConfigurations", true)
        tracker.onActivityCreated(first, null); tracker.onActivityStopped(first); tracker.onActivityDestroyed(first)
        val next = Robolectric.buildActivity(Activity::class.java).get()
        tracker.onActivityCreated(next, null)
        val windows = ColdStart::class.java.getDeclaredField("windows").apply { isAccessible = true }.get(tracker) as Map<*, *>
        try { assertTrue("Recreated Activity must still be tracked", windows.containsKey(next.window)) }
        finally { tracker.close() }
    }
    @Test fun realSdkLimitDropsSurvivePrivacyFiltering() {
        val sink = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(sink)).build()
        provider.get("test").spanBuilder("limited").startSpan().setAllAttributes(
            Attributes.builder().put("keep", true).put("network.carrier.name", "private").build()).end()
        val original = sink.finishedSpanItems.single()
        val limited = object : SpanData by original { override fun getTotalAttributeCount() = original.totalAttributeCount + 5 }
        val safe = Privacy.span(limited)
        assertEquals(5, safe.totalAttributeCount - safe.attributes.size())
        provider.close()
    }
    @Test fun processDetectionFailureReportsFailed() {
        PublicStartFailureTest().clearPublicOwner()
        val broken = object : Application() {
            override fun getApplicationInfo(): ApplicationInfo = error("process lookup unavailable")
        }
        try {
            EpokRum.start(broken, EpokRumConfig("http://127.0.0.1:9", "dummy", "test"))
            assertEquals(ConsentState.FAILED, EpokRum.consentState())
        } finally { PublicStartFailureTest().clearPublicOwner() }
    }
    @Test fun instrumentationLookupFailureDoesNotBreakHostRequest() {
        prefs.edit().putBoolean("granted", true).commit()
        val rum = ConsentController(app, direct, direct) {
            object : RumRuntime {
                override fun shutdown() {}
                override fun connectionInterceptor(): Interceptor = throw NoClassDefFoundError("optional instrumentation unavailable")
                override fun networkInterceptor(): Interceptor = error("instrumentation lookup failed")
            }
        }
        val field = EpokRum::class.java.getDeclaredField("controller").apply { isAccessible = true }
        field.set(null, rum)
        val request = okhttp3.Request.Builder().url("http://localhost/test").build()
        val response = okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").build()
        var calls = 0
        val chain = java.lang.reflect.Proxy.newProxyInstance(Interceptor.Chain::class.java.classLoader,
            arrayOf(Interceptor.Chain::class.java)) { _, method, args ->
            when (method.name) {
                "request" -> request
                "proceed" -> { assertSame(request, args!![0]); calls++; response }
                else -> error(method.name)
            }
        } as Interceptor.Chain
        try {
            assertSame(response, EpokRum.connectionInterceptor().intercept(chain))
            assertSame(response, EpokRum.networkInterceptor().intercept(chain))
            assertEquals(2, calls)
        } finally { field.set(null, null); rum.withdraw() }
    }
}
