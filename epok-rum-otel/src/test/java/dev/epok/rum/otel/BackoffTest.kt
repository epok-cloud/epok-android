package dev.epok.rum.otel

import android.app.Application
import okhttp3.Interceptor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackoffTest {
    private val app get() = RuntimeEnvironment.getApplication() as Application
    private val prefs get() = app.getSharedPreferences("epok-rum-otel-consent", 0)
    private val direct = Executor { it.run() }
    private var time = 1_000_000L
    private var attempts = 0
    @Before fun reset() { prefs.edit().clear().putBoolean("granted", true).commit() }
    private fun failing(version: Long = 1) = ConsentController(app, direct, direct, { time }, version) { attempts++; error("injected failure") }
    @Test fun retryWindowsAndFiveAttemptLimit() {
        failing(); failing() // first retry is the next process
        assertEquals(2, attempts)
        failing(); assertEquals(2, attempts)
        time += 3_600_000 - 1; failing(); assertEquals(2, attempts)
        time++; failing(); assertEquals(3, attempts)
        time += 86_400_000 - 1; failing(); assertEquals(3, attempts)
        time++; failing(); assertEquals(4, attempts)
        time += 86_400_000; failing(); assertEquals(5, attempts)
        time += 6 * 86_400_000L; failing(); assertEquals(5, attempts)
        assertTrue(prefs.getBoolean("granted", false))
    }
    @Test fun versionChangeAndSuccessResetFailureRecord() {
        failing(); failing(); failing(2); assertEquals(3, attempts)
        assertEquals(1, prefs.getInt("init_failures", 0))
        val healthy = ConsentController(app, direct, direct, { time }, 2) {
            object : RumRuntime {
                override fun shutdown() {}
                override fun connectionInterceptor() = Interceptor { it.proceed(it.request()) }
                override fun networkInterceptor() = connectionInterceptor()
            }
        }
        assertEquals(ConsentState.GRANTED, healthy.state())
        assertFalse(prefs.contains("init_failures")); healthy.withdraw()
    }
    @Test fun repeatedExplicitGrantCannotBypassBackoffOrRetrySameProcess() {
        val first = failing()
        repeat(10) { first.grant() }
        assertEquals(1, attempts); assertEquals(ConsentState.FAILED, first.state())
        failing()
        val blocked = failing()
        repeat(10) { blocked.grant() }
        assertEquals(2, attempts); assertEquals(ConsentState.FAILED, blocked.state())
        time += 3_600_000
        blocked.grant(); assertEquals(3, attempts)
    }
    @Test fun withdrawAfterFailureClearsChoiceButPreservesFailureRecord() {
        val rum = failing(); rum.withdraw()
        assertFalse(prefs.getBoolean("granted", true))
        assertEquals(1, prefs.getInt("init_failures", 0)); assertTrue(prefs.contains("last_failure_at"))
        assertTrue(prefs.contains("failure_version")); assertFalse(prefs.contains("init_in_progress"))
        assertEquals(ConsentState.DENIED, rum.state())
    }
    @Test fun failureDeactivatesPartialRuntimeBeforeCleanup() {
        val queued = mutableListOf<Runnable>(); var fenced = false; var stopped = false
        val rum = ConsentController(app, direct, Executor { queued += it }) {
            object : RumRuntime {
                override fun initialize() { error("injected partial initialization") }
                override fun deactivate() { fenced = true }
                override fun shutdown() { stopped = true }
                override fun connectionInterceptor() = Interceptor { it.proceed(it.request()) }
                override fun networkInterceptor() = connectionInterceptor()
            }
        }
        assertEquals(ConsentState.FAILED, rum.state()); assertTrue(fenced); assertFalse(stopped)
        assertNull(rum.activeRuntime()); assertEquals("Failed init must schedule cleanup", 1, queued.size)
        queued.single().run(); assertTrue(stopped)
    }
    @Test fun withdrawDoesNotAwaitShutdownOrFactory() {
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val rum = ConsentController(app, Executor { Thread(it).start() }) {
            entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            error("constructor cancelled")
        }
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(prefs.getBoolean("init_in_progress", false))
        val start = System.nanoTime()
        try { rum.withdraw(); assertTrue("Must not wait for the five-second blocked factory (device enforces 50 ms)", (System.nanoTime()-start)/1e6 < 1000) }
        finally { release.countDown() }
        assertEquals(ConsentState.DENIED, rum.state())
        assertFalse(prefs.contains("init_in_progress"))
    }
}
