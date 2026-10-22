package dev.epok.rum.otel

import android.app.Application
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import org.junit.After
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
class PublicStartFailureTest {
    @Before @After fun clearPublicOwner() {
        // No real SDK is created by this test. Only reset the wrapper's private test state.
        for (name in listOf("controller", "configuration", "startedApplication")) {
            EpokRum::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, null)
        }
        EpokRum::class.java.getDeclaredField("secondaryProcess").apply { isAccessible = true }.setBoolean(null, false)
        EpokRum::class.java.getDeclaredField("startFailed").apply { isAccessible = true }.setBoolean(null, false)
    }
    @Test fun preferenceFailureDoesNotEscapeStartOrWithdrawalAndBufferIsPurged() {
        val real = RuntimeEnvironment.getApplication()
        val app = object : Application() {
            override fun getApplicationInfo() = ApplicationInfo().apply { processName = Application.getProcessName() }
            override fun getCacheDir(): File = real.cacheDir
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                throw IllegalStateException("injected unavailable preferences")
        }
        val buffer = File(app.cacheDir, "opentelemetry/signals/pending")
        buffer.parentFile!!.mkdirs(); buffer.writeText("private")
        EpokRum.start(app, EpokRumConfig("http://127.0.0.1:9", "unit-dummy", "unit"))
        assertEquals(ConsentState.FAILED, EpokRum.consentState())
        EpokRum.grantConsent()
        assertEquals(ConsentState.FAILED, EpokRum.consentState())
        EpokRum.withdrawConsent()
        assertEquals(ConsentState.DENIED, EpokRum.consentState())
        val done = java.util.concurrent.CountDownLatch(1)
        Cleanup.execute { done.countDown() }
        assertTrue(done.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertFalse(File(app.cacheDir, "opentelemetry").exists())
    }
    @Test fun secondaryProcessConsentMethodsAreNoops() {
        val app = object : Application() {
            override fun getApplicationInfo() = ApplicationInfo().apply { processName = "different.main.process" }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = error("Secondary process must not touch shared storage")
        }
        EpokRum.start(app, EpokRumConfig("http://127.0.0.1:9", "unit-dummy", "unit"))
        EpokRum.grantConsent(); EpokRum.withdrawConsent()
        assertEquals(ConsentState.NOT_MAIN_PROCESS, EpokRum.consentState())
    }
}
