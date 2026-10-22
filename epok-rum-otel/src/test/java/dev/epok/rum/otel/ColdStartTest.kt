package dev.epok.rum.otel

import android.app.Activity
import android.app.ActivityManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ColdStartTest {
    private fun setImportance(importance: Int) {
        val manager = RuntimeEnvironment.getApplication().getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager
        org.robolectric.Shadows.shadowOf(manager).setProcesses(listOf(ActivityManager.RunningAppProcessInfo().apply {
            pid = android.os.Process.myPid()
            this.importance = importance
        }))
    }
    private fun foreground() {
        setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
    }
    @Test fun trampolineDoesNotFinishTracker() {
        foreground()
        val tracker = ColdStart(RuntimeEnvironment.getApplication()) { _, _, _ -> }
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        tracker.onActivityCreated(activity, null)
        tracker.onActivityDestroyed(activity)
        val finished = ColdStart::class.java.getDeclaredField("finished").apply { isAccessible = true }
        assertFalse(finished.getBoolean(tracker))
        tracker.close()
    }
    @Test fun delayedActivityIsNotColdStart() {
        foreground()
        val tracker = ColdStart(RuntimeEnvironment.getApplication()) { _, _, _ -> fail("background start") }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
        tracker.onActivityCreated(Robolectric.buildActivity(Activity::class.java).get(), null)
        val finished = ColdStart::class.java.getDeclaredField("finished").apply { isAccessible = true }
        assertTrue(finished.getBoolean(tracker))
    }
    @Test fun backgroundProcessIsIneligible() {
        setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE)
        assertFalse(ColdStart.eligibleLaunch(RuntimeEnvironment.getApplication()))
    }
    @Test fun cachedStartupImportanceAllowsTimelyActivity() {
        // API 34 can report cached importance in Application.onCreate during a cold UI launch.
        setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED)
        val tracker = ColdStart(RuntimeEnvironment.getApplication()) { _, _, _ -> }
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        tracker.onActivityCreated(activity, null)
        val windows = ColdStart::class.java.getDeclaredField("windows").apply { isAccessible = true }
        try { assertEquals(1, (windows.get(tracker) as Map<*, *>).size) }
        finally { tracker.close() }
    }
    @Test fun cachedProcessWithDelayedActivityIsNotCold() {
        setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED)
        val tracker = ColdStart(RuntimeEnvironment.getApplication()) { _, _, _ -> fail("late cached process") }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
        tracker.onActivityCreated(Robolectric.buildActivity(Activity::class.java).get(), null)
        val windows = ColdStart::class.java.getDeclaredField("windows").apply { isAccessible = true }
        try { assertTrue((windows.get(tracker) as Map<*, *>).isEmpty()) }
        finally { tracker.close() }
    }
}
