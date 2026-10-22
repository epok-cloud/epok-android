package dev.epok.rum.otel

import android.app.Application
import android.content.Context
import okhttp3.Interceptor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConsentTest {
    private lateinit var app: Application
    private var constructions = 0
    private var shutdowns = 0
    private val cold = mutableListOf<Boolean>()
    private fun controller() = ConsentController(app, java.util.concurrent.Executor { it.run() }, cleanup = java.util.concurrent.Executor { it.run() }) { launch ->
        constructions++
        cold += launch
        object : RumRuntime {
            override fun shutdown() { shutdowns++ }
            override fun connectionInterceptor() = Interceptor { it.proceed(it.request()) }
            override fun networkInterceptor() = connectionInterceptor()
        }
    }
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("epok-rum-otel-consent", 0).edit().clear().commit()
        app.getSharedPreferences("opentelemetry-android", 0).edit().clear().commit()
        File(app.cacheDir, "opentelemetry").deleteRecursively()
    }
    @Test fun initExceptionDoesNotEscapeAndWithdrawalStillWorks() {
        app.getSharedPreferences("epok-rum-otel-consent", 0).edit().putBoolean("granted", true).commit()
        val rum = ConsentController(app, java.util.concurrent.Executor { it.run() }, cleanup = java.util.concurrent.Executor { it.run() }) { throw IllegalStateException("injected init failure") }
        assertEquals(ConsentState.FAILED, rum.state())
        rum.withdraw()
        assertEquals(ConsentState.DENIED, rum.state())
        assertFalse(File(app.cacheDir, "opentelemetry").exists())
    }
    @Test fun initTimeoutClosesPartialRuntimeAndReportsFailed() {
        var closed = false
        app.getSharedPreferences("epok-rum-otel-consent", 0).edit().putBoolean("granted", true).commit()
        val rum = ConsentController(app, java.util.concurrent.Executor { it.run() }, cleanup = java.util.concurrent.Executor { it.run() }) {
            object : RumRuntime {
                override fun initialize() { OtelBridge.DiskSchedule {}.awaitReady(1, java.util.concurrent.TimeUnit.MILLISECONDS) }
                override fun shutdown() { closed = true }
                override fun connectionInterceptor() = Interceptor { it.proceed(it.request()) }
                override fun networkInterceptor() = connectionInterceptor()
            }
        }
        assertEquals(ConsentState.FAILED, rum.state())
        assertTrue(closed)
        assertNull(rum.activeRuntime())
        assertTrue(app.getSharedPreferences("epok-rum-otel-consent", 0).getBoolean("granted", false))
        rum.withdraw()
        assertEquals(ConsentState.DENIED, rum.state())
        rum.grant()
        assertEquals(ConsentState.GRANTED_PENDING_RESTART, rum.state())
    }
    @Test fun asynchronousInitDoesNotBlockAndWithdrawCancelsQueuedConstruction() {
        val tasks = mutableListOf<Runnable>()
        app.getSharedPreferences("epok-rum-otel-consent", 0).edit().putBoolean("granted", true).commit()
        val rum = ConsentController(app, java.util.concurrent.Executor { tasks += it }, cleanup = java.util.concurrent.Executor { it.run() }) {
            error("Withdraw must cancel initialization before constructing OTel")
        }
        assertEquals(ConsentState.INITIALIZING, rum.state())
        assertNull(rum.activeRuntime())
        rum.withdraw()
        tasks.single().run()
        assertEquals(ConsentState.DENIED, rum.state())
        assertFalse(File(app.cacheDir, "opentelemetry").exists())
    }
    @Test fun rejectedInitializationExecutorStillAllowsWithdrawal() {
        // A rejected executor is another initialization failure before a runtime exists.
        val rum = ConsentController(app, java.util.concurrent.Executor { throw java.util.concurrent.RejectedExecutionException() }) { error("unused") }
        rum.grant()
        assertEquals(ConsentState.FAILED, rum.state())
        rum.withdraw()
        assertEquals(ConsentState.DENIED, rum.state())
    }
    @Test fun lateInitFailureDoesNotOverwriteRegrantAfterWithdrawal() {
        val queued = mutableListOf<Runnable>()
        val entered = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val rum = ConsentController(app, java.util.concurrent.Executor { queued += it }, cleanup = java.util.concurrent.Executor { it.run() }) {
            object : RumRuntime {
                override fun initialize() {
                    entered.countDown()
                    check(finish.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    throw IllegalStateException("late cancelled initialization")
                }
                override fun shutdown() {}
                override fun connectionInterceptor() = Interceptor { it.proceed(it.request()) }
                override fun networkInterceptor() = connectionInterceptor()
            }
        }
        rum.grant()
        val worker = Thread(queued.single())
        worker.start()
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        rum.withdraw()
        rum.grant()
        finish.countDown()
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertEquals(ConsentState.GRANTED_PENDING_RESTART, rum.state())
        assertTrue(app.getSharedPreferences("epok-rum-otel-consent", 0).getBoolean("granted", false))
    }
    @Test fun consentGateConstructsNothingBeforeGrant() {
        val rum = controller()
        assertEquals(0, constructions)
        assertNull(rum.activeRuntime())
        assertEquals(ConsentState.DENIED, rum.state())
        assertFalse(File(app.cacheDir, "opentelemetry").exists())
        rum.grant()
        assertEquals(1, constructions)
        assertEquals(listOf(false), cold)
        assertNotNull(rum.activeRuntime())
    }
    @Test fun grantIsIdempotentAndPersisted() {
        val rum = controller()
        rum.grant(); rum.grant()
        assertEquals(1, constructions)
        assertTrue(app.getSharedPreferences("epok-rum-otel-consent", 0).getBoolean("granted", false))
        assertEquals(ConsentState.GRANTED, rum.state())
    }
    @Test fun coldStartWithPersistedGrantStartsImmediately() {
        app.getSharedPreferences("epok-rum-otel-consent", 0).edit().putBoolean("granted", true).commit()
        val rum = controller()
        assertEquals(1, constructions)
        assertEquals(listOf(true), cold)
        assertEquals(ConsentState.GRANTED, rum.state())
    }
    @Test fun withdrawShutsDownPurgesBufferAndInstallIdAndPersistsDenied() {
        val rum = controller(); rum.grant()
        val buffer = File(app.cacheDir, "opentelemetry/signals/logs/crash")
        buffer.parentFile!!.mkdirs(); buffer.writeText("pending")
        app.getSharedPreferences("opentelemetry-android", 0).edit().putString("app.installation.id", "old-id").commit()
        rum.withdraw(); rum.withdraw()
        assertEquals(1, shutdowns)
        assertNull(rum.activeRuntime())
        assertFalse(File(app.cacheDir, "opentelemetry").exists())
        assertFalse(app.getSharedPreferences("opentelemetry-android", 0).contains("app.installation.id"))
        assertFalse(app.getSharedPreferences("epok-rum-otel-consent", 0).getBoolean("granted", true))
    }
    @Test fun withdrawClearsCachedInstallationIdEvenBeforeItsFileExists() {
        val rum = controller(); rum.grant()
        val sdkPrefs = app.getSharedPreferences("opentelemetry-android", Context.MODE_PRIVATE)
        sdkPrefs.edit().putString("app.installation.id", "in-memory-id").apply()
        File(app.applicationInfo.dataDir, "shared_prefs/opentelemetry-android.xml").delete()
        rum.withdraw()
        assertFalse(sdkPrefs.contains("app.installation.id"))
    }
    @Test fun regrantDefersFactoryUntilNextProcess() {
        val rum = controller(); rum.grant(); rum.withdraw(); rum.grant()
        assertEquals(1, constructions)
        assertEquals(ConsentState.GRANTED_PENDING_RESTART, rum.state())
        assertNull(rum.activeRuntime())
        assertTrue(app.getSharedPreferences("epok-rum-otel-consent", 0).getBoolean("granted", false))
        // A new controller models a genuinely new process, not a public reset API.
        val restarted = controller()
        assertEquals(2, constructions)
        assertEquals(ConsentState.GRANTED, restarted.state())
        assertEquals(listOf(false, true), cold)
    }
    @Test fun deniedColdStartFinishesInterruptedPurge() {
        val file = File(app.cacheDir, "opentelemetry/signals/logs/stale")
        file.parentFile!!.mkdirs(); file.writeText("private")
        controller()
        assertFalse(file.exists())
        assertEquals(0, constructions)
    }
    @Test fun withdrawingBeforeAnyGrantStillAllowsFirstGrant() {
        val rum = controller(); rum.withdraw(); rum.grant()
        assertEquals(1, constructions)
        assertEquals(ConsentState.GRANTED, rum.state())
    }
    @Test fun pendingGrantCanBeWithdrawnBeforeRestart() {
        val rum = controller(); rum.grant(); rum.withdraw(); rum.grant(); rum.withdraw()
        assertEquals(ConsentState.DENIED, controller().state())
        assertEquals(1, constructions)
    }
}
