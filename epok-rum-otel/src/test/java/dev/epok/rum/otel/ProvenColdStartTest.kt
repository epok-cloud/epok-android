package dev.epok.rum.otel

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationStartInfo
import android.os.Process
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ProvenColdStartTest.StartReasons::class])
class ProvenColdStartTest {
    @Implements(ActivityManager::class)
    class StartReasons : ShadowActivityManager() {
        @Implementation fun getHistoricalProcessStartReasons(maxNum: Int): List<ApplicationStartInfo> {
            val info = ReflectionHelpers.callConstructor(ApplicationStartInfo::class.java)
            ReflectionHelpers.callInstanceMethod<Void>(info, "setPid", ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Process.myPid()))
            ReflectionHelpers.callInstanceMethod<Void>(info, "setStartType", ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, ApplicationStartInfo.START_TYPE_COLD))
            ReflectionHelpers.callInstanceMethod<Void>(info, "setReason", ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, ApplicationStartInfo.START_REASON_LAUNCHER))
            return listOf(info)
        }
    }
    @Test fun provenSlowActivityIsStillTracked() {
        val tracker = ColdStart(RuntimeEnvironment.getApplication()) { _, _, _ -> }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
        tracker.onActivityCreated(Robolectric.buildActivity(Activity::class.java).get(), null)
        val finished = ColdStart::class.java.getDeclaredField("finished").apply { isAccessible = true }
        try { assertFalse("Proven cold launch may exceed the fallback's two-second bound", finished.getBoolean(tracker)) }
        finally { tracker.close() }
    }
}
