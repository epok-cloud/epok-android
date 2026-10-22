package dev.epok.rum.otel

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Process
import okhttp3.Interceptor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Fix4HealthTest {
    private val app get() = RuntimeEnvironment.getApplication() as Application
    private val prefs get() = app.getSharedPreferences("epok-rum-otel-consent", 0)
    private val queued = mutableListOf<Runnable>()
    private val direct = Executor { it.run() }
    private var time = 1_000_000_000L
    @Before fun reset() { prefs.edit().clear().putBoolean("granted", true).commit() }
    private fun marker() { prefs.edit().putInt("init_failures", 1).putLong("failure_version", 7)
        .putLong("last_failure_at", time).putBoolean("init_in_progress", true).putLong("init_version", 7).putInt("init_pid", 42).commit() }
    private fun controller() = ConsentController(app, Executor { queued += it }, direct, { time }, 7) { healthy() }
    private fun healthy() = object : RumRuntime {
        override fun shutdown() {}
        override fun connectionInterceptor() = Interceptor { it.proceed(it.request()) }
        override fun networkInterceptor() = connectionInterceptor()
    }
    private fun exit(reason: Int, pid: Int = 42) {
        val manager = app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(manager).addApplicationExitInfo(app.packageName, pid, reason, 0)
    }
    @Test fun closeAtomicallyNeutralizesWithoutReplacingGlobalHandler() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            var delegated = 0
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> delegated++ }
            val guard = InitCrashGuard(prefs)
            marker()
            guard.close()
            assertSame("Close must not race a host installing a new handler", guard, Thread.getDefaultUncaughtExceptionHandler())
            guard.uncaughtException(Thread.currentThread(), RuntimeException("host"))
            assertTrue(prefs.getBoolean("init_in_progress", false))
            assertEquals(1, delegated)
            val replacement = Thread.UncaughtExceptionHandler { _, _ -> }
            Thread.setDefaultUncaughtExceptionHandler(replacement)
            guard.close()
            assertSame(replacement, Thread.getDefaultUncaughtExceptionHandler())
        } finally { Thread.setDefaultUncaughtExceptionHandler(original) }
    }
    @Test fun hostIOExceptionWithInterceptorFramesDoesNotCount() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        fun frame(name: String) = StackTraceElement(name, "intercept", "Network.kt", 1)
        try {
            for (frames in listOf(
                arrayOf(frame("java.net.Socket"), frame("com.host.Client"), frame("dev.epok.rum.otel.EpokRum")),
                arrayOf(frame("io.opentelemetry.instrumentation.okhttp.v3_0.TracingInterceptor"), frame("com.host.Client")),
                arrayOf(frame("io.opentelemetry.instrumentation.library.okhttp.internal.OkHttpSingletons"), frame("com.host.Client")),
                arrayOf(frame("io.opentelemetry.android.instrumentation.okhttp.TracingInterceptor"), frame("com.host.Client")),
                arrayOf(frame("dev.epok.rum.otel.EpokRum"), frame("com.host.Client")),
                arrayOf(frame("dev.epok.rum.otel.HostInterceptors\$Scoped"), frame("com.host.Client")))) {
                marker()
                var remained = true
                Thread.setDefaultUncaughtExceptionHandler { _, _ -> remained = prefs.getBoolean("init_in_progress", false) }
                val guard = InitCrashGuard(prefs)
                val cause = java.io.IOException("host IO").apply { stackTrace = frames }
                val error = RuntimeException("wrapper", cause).apply { stackTrace = arrayOf(frame("io.opentelemetry.android.Factory")) }
                guard.uncaughtException(Thread.currentThread(), error); guard.close()
                assertFalse("Host cause must win over wrapper/interceptor frames", remained)
            }
        } finally { Thread.setDefaultUncaughtExceptionHandler(original) }
    }
    @Test fun resetApiIsSynchronizedAndStorageFailureDoesNotEscape() {
        assertTrue("Public reset shares start monitor", java.lang.reflect.Modifier.isSynchronized(EpokRum::class.java.getMethod("resetInitFailures").modifiers))
        PublicStartFailureTest().clearPublicOwner()
        val failing = object : Application() {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences = throw IllegalStateException("storage unavailable")
        }
        EpokRum::class.java.getDeclaredField("startedApplication").apply { isAccessible=true }.set(null, failing)
        try { EpokRum.resetInitFailures() } finally { PublicStartFailureTest().clearPublicOwner() }
    }
    @Test fun normalAndUnknownDeathsDoNotCount() {
        for (reason in listOf(ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED, ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_UNKNOWN)) {
            prefs.edit().putBoolean("granted", true).commit(); ConsentStorage.allow(app); marker(); exit(reason)
            val rum = controller()
            assertEquals("Reason $reason", 1, prefs.getInt("init_failures", 0))
            assertEquals("The grant must reach init for every reason", ConsentState.INITIALIZING, rum.state())
            assertFalse(prefs.contains("init_in_progress")); rum.withdraw()
        }
    }
    @Test fun onlyRecordedPidCrashNativeAndAnrCount() {
        for (reason in listOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)) {
            prefs.edit().putBoolean("granted", true).commit(); ConsentStorage.allow(app); marker(); exit(reason)
            val rum = controller()
            assertEquals(2, prefs.getInt("init_failures", 0)); assertEquals(ConsentState.FAILED, rum.state())
            assertFalse(prefs.contains("init_in_progress"))
        }
    }
    @Test fun missingOrDifferentPidDoesNotCount() {
        marker(); exit(ApplicationExitInfo.REASON_CRASH, 99)
        val rum = controller(); assertEquals(1, prefs.getInt("init_failures", 0)); rum.withdraw()
    }
    @Test @Config(sdk = [28]) fun oldApiCountsUnexplainedDeath() {
        marker(); val rum = controller(); assertEquals(2, prefs.getInt("init_failures", 0)); assertEquals(ConsentState.FAILED, rum.state())
    }
    @Test fun sevenDayDecayResetsCap() {
        prefs.edit().putInt("init_failures", 5).putLong("failure_version", 7).putLong("last_failure_at", time-7*86_400_000L-1).commit()
        val rum = controller(); assertEquals(ConsentState.INITIALIZING, rum.state()); assertFalse(prefs.contains("init_failures")); rum.withdraw()
    }
    @Test fun hostCrashClearsMarkerBeforeDelegatingButEpokCauseKeepsIt() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            for (namespace in listOf("com.host.App", "io.opentelemetry.Factory", "dev.epok.rum.otel.Factory")) {
                val epok = namespace != "com.host.App"
                prefs.edit().clear().putBoolean("granted", true).commit()
                ConsentStorage.allow(app) // Simulate a fresh user grant after the prior loop withdrew.
                var delegated = false
                var markerAtDelegate: Boolean? = null
                val host = Thread.UncaughtExceptionHandler { _, _ ->
                    delegated = true; markerAtDelegate = prefs.getBoolean("init_in_progress", false)
                }
                Thread.setDefaultUncaughtExceptionHandler(host)
                val rum = ConsentController(app, direct, direct, { time }, 7) {
                    assertEquals(Process.myPid(), prefs.getInt("init_pid", -1))
                    val cause = RuntimeException("cause").apply { stackTrace = arrayOf(StackTraceElement(namespace, "run", "App.kt", 1)) }
                    val error = RuntimeException("host", cause).apply { stackTrace = arrayOf(StackTraceElement("com.host.App", "run", "App.kt", 2)) }
                    Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
                    healthy()
                }
                assertTrue(delegated); assertEquals(epok, markerAtDelegate); assertTrue(Thread.getDefaultUncaughtExceptionHandler() is InitCrashGuard); rum.withdraw()
            }
        } finally { Thread.setDefaultUncaughtExceptionHandler(original) }
    }
    @Test @Config(sdk = [28]) fun oldApiHostCrashIsAlsoClearedBeforeDelegation() {
        hostCrashClearsMarkerBeforeDelegatingButEpokCauseKeepsIt()
    }
    @Test fun unavailableExitReasonsDoNotCount() {
        assertFalse(InitHealth.countPreviousDeath(app, -1))
        val unavailable = object : Application() {
            override fun getPackageName() = app.packageName
            override fun getSystemService(name: String): Any = throw SecurityException("unavailable")
        }
        assertFalse(InitHealth.countPreviousDeath(unavailable, 42))
    }
    @Test fun persistenceDoesNotHoldStateMonitor() {
        prefs.edit().putBoolean("granted", false).commit()
        var owner: ConsentController? = null
        val underMonitor = mutableListOf<Boolean>()
        val wrapped = object : Application() {
            override fun getCacheDir() = app.cacheDir
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val backing = app.getSharedPreferences(name, mode)
                return java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
                    arrayOf(android.content.SharedPreferences::class.java)) { _, method, args ->
                    if (method.name != "edit") method.invoke(backing, *(args ?: emptyArray()))
                    else {
                        val editor = backing.edit()
                        java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
                            arrayOf(android.content.SharedPreferences.Editor::class.java)) { proxy, call, values ->
                            if (name in setOf("epok-rum-otel-consent", "epok-rum-otel-withdrawal") && call.name == "commit" && owner != null) underMonitor += Thread.holdsLock(owner!!)
                            val result = call.invoke(editor, *(values ?: emptyArray()))
                            if (result === editor) proxy else result
                        }
                    }
                } as android.content.SharedPreferences
            }
        }
        val rum = ConsentController(wrapped, Executor { queued += it }, direct, { time }, 7) { healthy() }
        owner = rum
        rum.grant(); queued.single().run(); rum.withdraw()
        assertTrue(underMonitor.size >= 4)
        assertFalse("Durable fsync must not hold the state/first-frame monitor", underMonitor.any { it })
    }
    @Test fun developerResetPreservesConsentAndInFlightMarker() {
        val rum = controller()
        prefs.edit().putInt("init_failures", 5).putLong("last_failure_at", time).putLong("failure_version", 7)
            .putBoolean("init_in_progress", true).putInt("init_pid", 42).commit()
        PublicStartFailureTest().clearPublicOwner()
        EpokRum::class.java.getDeclaredField("startedApplication").apply { isAccessible=true }.set(null, app)
        EpokRum::class.java.getDeclaredField("controller").apply { isAccessible=true }.set(null, rum)
        try {
            val reset = EpokRum::class.java.methods.firstOrNull { it.name == "resetInitFailures" }
            assertNotNull("Support reset API", reset); reset!!.invoke(EpokRum)
            assertFalse(prefs.contains("init_failures")); assertTrue(prefs.getBoolean("granted", false))
            assertTrue(prefs.getBoolean("init_in_progress", false))
        } finally { rum.withdraw(); PublicStartFailureTest().clearPublicOwner() }
    }
}
