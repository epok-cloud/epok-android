package dev.epok.rum.otel

import android.app.Application
import android.content.SharedPreferences
import okhttp3.Interceptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Fix8HealthTest {
    private val app get() = RuntimeEnvironment.getApplication() as Application
    @Test fun missingDefaultHandlerKillsThenExits() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(null)
        val calls = mutableListOf<String>()
        val guard = InitCrashGuard(app.getSharedPreferences("test", 0), { calls.add("kill:$it") }, { calls.add("exit:$it") })
        try {
            guard.uncaughtException(Thread.currentThread(), IllegalStateException("host crash"))
            assertEquals(listOf("kill:${android.os.Process.myPid()}", "exit:10"), calls)
        } finally { guard.close(); Thread.setDefaultUncaughtExceptionHandler(previous) }
    }
    @Test fun durableDenialWinsOverStaleGrantAfterProcessDeath() {
        val prefs = app.getSharedPreferences("epok-rum-otel-consent", 0)
        ConsentStorage.deny(app)
        // Model process death before the asynchronous mirror reaches disk.
        prefs.edit().putBoolean("granted", true).commit()
        val rum = ConsentController(app, java.util.concurrent.Executor { it.run() }, java.util.concurrent.Executor { it.run() }) {
            fail("A durable denial must prevent construction after restart")
            throw AssertionError()
        }
        assertEquals(ConsentState.DENIED, rum.state())
        assertFalse(prefs.getBoolean("granted", true))
    }
    @Test fun withdrawDoesNotWaitForInitCommitAndNeverEnablesExport() {
        val prefs = app.getSharedPreferences("epok-rum-otel-consent", 0)
        prefs.edit().clear().putBoolean("granted", true).commit()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val blocked = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val edit = prefs.edit()
                var marker = false
                return object : SharedPreferences.Editor by edit {
                    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                        if (key == "init_in_progress" && value) marker = true
                        edit.putBoolean(key, value); return this
                    }
                    override fun putLong(key: String, value: Long): SharedPreferences.Editor { edit.putLong(key, value); return this }
                    override fun putInt(key: String, value: Int): SharedPreferences.Editor { edit.putInt(key, value); return this }
                    override fun commit(): Boolean {
                        if (marker) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
                        return edit.commit()
                    }
                }
            }
        }
        val worker = Executors.newSingleThreadExecutor()
        var enabled = false
        val rum = ConsentController(app, worker, java.util.concurrent.Executor { it.run() }, prefs = blocked) {
            object : RumRuntime {
                override fun enable() { enabled = true }
                override fun shutdown() {}
                override fun connectionInterceptor() = Interceptor { it.proceed(it.request()) }
                override fun networkInterceptor() = connectionInterceptor()
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // A real blocked commit on the initialization worker; release after 300 ms so
            // the broken implementation fails by a measured assertion rather than hanging.
            val unblocker = Thread { Thread.sleep(300); release.countDown() }.apply { start() }
            assertSame("Measure on the Android main thread", android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
            val started = System.nanoTime()
            rum.withdraw()
            val duration = (System.nanoTime()-started)/1e6
            println("withdraw_with_slow_init_commit_ms=$duration")
            assertEquals(ConsentState.DENIED, rum.state())
            unblocker.join()
            worker.execute { done.countDown() }; assertTrue(done.await(5, TimeUnit.SECONDS))
            assertFalse(enabled)
            assertFalse(prefs.getBoolean("granted", true))
            assertNull(rum.activeRuntime())
            assertTrue("withdraw took $duration ms", duration < 50)
        } finally { release.countDown(); worker.shutdownNow() }
    }
}
