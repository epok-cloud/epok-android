package dev.epok.rum.otel

import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [StartClockTest.StartProcess::class])
class StartClockTest {
    @Implements(Process::class)
    class StartProcess : org.robolectric.shadows.ShadowProcess() {
        companion object {
            @JvmStatic @Implementation fun getStartUptimeMillis() = 200L
            @JvmStatic @Implementation fun getStartRequestedUptimeMillis() = requested
            @JvmField var requested = 100L
        }
    }
    @org.junit.Before fun resetClock() { StartProcess.requested = 100L }
    @Test fun invalidRequestedTimeFallsBackToFork() {
        for (value in listOf(0L, -1L, 201L)) {
            StartProcess.requested = value
            val tracker = ColdStart(RuntimeEnvironment.getApplication()) { _, _, _ -> }
            val start = ColdStart::class.java.getDeclaredField("processStart").apply { isAccessible = true }
            try { assertEquals(200L, start.getLong(tracker)) } finally { tracker.close() }
        }
    }
    @Test fun api33UsesRequestTimeInsteadOfForkTime() {
        val tracker = ColdStart(RuntimeEnvironment.getApplication()) { _, _, _ -> }
        val start = ColdStart::class.java.getDeclaredField("processStart").apply { isAccessible = true }
        try { assertEquals(100L, start.getLong(tracker)) } finally { tracker.close() }
    }
    @Test @Config(sdk = [28]) fun olderApiUsesForkTime() {
        val tracker = ColdStart(RuntimeEnvironment.getApplication()) { _, _, _ -> }
        val start = ColdStart::class.java.getDeclaredField("processStart").apply { isAccessible = true }
        try { assertEquals(200L, start.getLong(tracker)) } finally { tracker.close() }
    }
}
