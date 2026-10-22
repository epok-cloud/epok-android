package dev.epok.rum.otel

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BridgeLifecycleTest {
    @Test fun mainThreadWithdrawalCancelsQueuedLifecycleRegistrationWithoutDeadlock() {
        val app = RuntimeEnvironment.getApplication()
        val bridge = OtelBridge(app, EpokRumConfig("http://127.0.0.1:9", "unit-dummy", "unit"))
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try { bridge.initialize() } catch (error: Throwable) { failure.set(error) }
        }
        worker.start()
        val queued = OtelBridge::class.java.getDeclaredField("mainTask").apply { isAccessible = true }
        val lock = checkNotNull(OtelBridge::class.java.getDeclaredField("mainTaskLock").apply { isAccessible = true }.get(bridge))
        val deadline = System.nanoTime() + 5_000_000_000
        while (synchronized(lock) { queued.get(bridge) == null } && worker.isAlive && System.nanoTime() < deadline) Thread.sleep(5)
        assertNotNull("Expected a lifecycle task waiting for the main thread", synchronized(lock) { queued.get(bridge) })
        // The main looper deliberately hasn't executed that task. shutdown must cancel it
        // before it waits for the initialization worker, or these two threads deadlock.
        val started = System.nanoTime()
        bridge.shutdown()
        worker.join(1000)
        assertFalse(worker.isAlive)
        assertTrue((System.nanoTime() - started) < 1_000_000_000)
        assertNotNull(failure.get())
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(File(app.cacheDir, "opentelemetry").exists())
    }

    @Test fun lateDiskReadinessAfterShutdownPurgesRecreatedFiles() {
        val app = RuntimeEnvironment.getApplication()
        val schedule = OtelBridge.DiskSchedule { ConsentStorage.purge(app) }
        schedule.disable()
        val file = File(app.cacheDir, "opentelemetry/signals/logs/late")
        file.parentFile!!.mkdirs()
        file.writeText("late initializer data")
        schedule.enable()
        assertFalse(File(app.cacheDir, "opentelemetry").exists())
    }
    @Test fun providerLivesUntilQueuedLifecycleUninstallRuns() {
        val app = RuntimeEnvironment.getApplication()
        val bridge = OtelBridge(app, EpokRumConfig("http://127.0.0.1:9", "unit-dummy", "unit"))
        val provider = io.opentelemetry.sdk.trace.SdkTracerProvider.builder().build()
        val sdk = io.opentelemetry.sdk.OpenTelemetrySdk.builder().setTracerProvider(provider).build()
        OtelBridge::class.java.getDeclaredField("sdk").apply { isAccessible = true }.set(bridge, sdk)
        val worker = Thread { bridge.shutdown() }.apply { start() }
        // The main thread deliberately has not dispatched the queued lifecycle uninstall.
        val main = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        val deadline = System.nanoTime() + 5_000_000_000L
        while (main.isIdle && worker.isAlive && System.nanoTime() < deadline) Thread.sleep(1)
        assertFalse("Expected a queued lifecycle cleanup", main.isIdle)
        val span = sdk.getTracer("probe").spanBuilder("lifecycle-callback").startSpan()
        try { assertTrue("Callbacks must still have a live provider until uninstall runs", span.isRecording) }
        finally {
            span.end()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            worker.join(2000)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertFalse(worker.isAlive)
    }
    @Test fun revokedCrashBypassesSdkReporterAndFlushHandler() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var hostCalls = 0
        var sdkCalls = 0
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> hostCalls++ }
            val bridge = OtelBridge(RuntimeEnvironment.getApplication(), EpokRumConfig("http://127.0.0.1:9", "unit-dummy", "unit"))
            bridge.fenceCrashHandler { _, _ -> sdkCalls++; hostCalls++ }
            bridge.enable()
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), RuntimeException("granted"))
            assertEquals(1, sdkCalls); assertEquals(1, hostCalls)
            bridge.deactivate()
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), RuntimeException("denied"))
            assertEquals(1, sdkCalls); assertEquals(2, hostCalls)
        } finally { Thread.setDefaultUncaughtExceptionHandler(original) }
    }
}
