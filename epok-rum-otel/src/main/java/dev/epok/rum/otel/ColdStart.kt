package dev.epok.rum.otel

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationStartInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window

/** Captures the first frame on the main thread even if the SDK is still initializing. */
internal class ColdStart(
    private val app: Application,
    private val emit: (Long, Long, String) -> Unit
) : Application.ActivityLifecycleCallbacks {
    private val windows = mutableMapOf<Window, String>()
    @Volatile private var finished = false
    private val handler = Handler(Looper.getMainLooper())
    private val launch = if (ActivityHistory.created) Eligibility.INELIGIBLE else launchEligibility(app)
    private val eligible = launch != Eligibility.INELIGIBLE
    private val forkTime = Process.getStartUptimeMillis()
    private val requestedTime = if (Build.VERSION.SDK_INT >= 33) Process.getStartRequestedUptimeMillis() else 0L
    private val processStart = if (requestedTime > 0 && requestedTime <= forkTime) requestedTime else forkTime
    private val deadline = Runnable { close() }
    private val abandoned = Runnable { if (windows.isEmpty()) close() }
    private var sawActivity = false
    private val listener = Window.OnFrameMetricsAvailableListener { window, metrics, _ ->
        if (!finished && window in windows && metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) {
            val end = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) + metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (end - processStart * 1_000_000 > 60_000_000_000L) { close(); return@OnFrameMetricsAvailableListener }
            val now = SystemClock.uptimeMillis() * 1_000_000
            val duration = (end - processStart * 1_000_000).coerceAtLeast(0)
            val epochEnd = System.currentTimeMillis() * 1_000_000 - (now - end)
            android.util.Log.i("EpokRum", "cold_start_clocks api=${Build.VERSION.SDK_INT} requested_uptime_ms=$requestedTime fork_uptime_ms=$forkTime selected_uptime_ms=$processStart end_uptime_ms=${end / 1e6}")
            emit(epochEnd - duration, epochEnd, windows.getValue(window))
            close()
        }
    }
    init {
        if (eligible) {
            app.registerActivityLifecycleCallbacks(this)
            handler.postDelayed(deadline, (60_000 - (SystemClock.uptimeMillis() - processStart)).coerceAtLeast(0))
        }
    }
    fun close() {
        finished = true
        handler.removeCallbacks(deadline)
        handler.removeCallbacks(abandoned)
        val remove = Runnable {
            val began = SystemClock.elapsedRealtimeNanos()
            try {
                windows.keys.forEach { it.removeOnFrameMetricsAvailableListener(listener) }
                windows.clear()
                app.unregisterActivityLifecycleCallbacks(this)
            } finally {
                android.util.Log.i("EpokRum", "cold_start_cleanup_ms=${(SystemClock.elapsedRealtimeNanos()-began)/1e6}")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) remove.run() else handler.post(remove)
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {
        if (!eligible || finished) return
        // Also bound the fallback when start-reason information isn't available yet.
        if (SystemClock.uptimeMillis() - processStart > 60_000 ||
            (!sawActivity && launch == Eligibility.IMPORTANCE && SystemClock.uptimeMillis() - forkTime > 2_000)) {
            close()
            return
        }
        handler.removeCallbacks(abandoned)
        sawActivity = true
        windows[activity.window] = activity.javaClass.simpleName
        activity.window.addOnFrameMetricsAvailableListener(listener, handler)
    }
    override fun onActivityDestroyed(activity: Activity) {
        // A trampoline can finish without drawing. Continue tracking the next Activity.
        if (windows.remove(activity.window) != null) activity.window.removeOnFrameMetricsAvailableListener(listener)
        if (!finished && windows.isEmpty()) handler.postDelayed(abandoned, 2_000)
    }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) {
        if (activity.window !in windows) return
        if (activity.isFinishing || activity.isChangingConfigurations) onActivityDestroyed(activity) else close()
    }
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

    companion object {
        internal enum class Eligibility { INELIGIBLE, PROVEN_ACTIVITY, IMPORTANCE }
        internal fun eligibleLaunch(app: Application) = launchEligibility(app) != Eligibility.INELIGIBLE
        internal fun launchEligibility(app: Application): Eligibility {
            if (Build.VERSION.SDK_INT >= 35) {
                val info = try {
                    (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                        .getHistoricalProcessStartReasons(0).firstOrNull { it.pid == Process.myPid() }
                } catch (_: RuntimeException) { null }
                if (info != null) return if (info.startType == ApplicationStartInfo.START_TYPE_COLD &&
                    info.reason in setOf(ApplicationStartInfo.START_REASON_START_ACTIVITY,
                        ApplicationStartInfo.START_REASON_LAUNCHER, ApplicationStartInfo.START_REASON_LAUNCHER_RECENTS))
                    Eligibility.PROVEN_ACTIVITY else Eligibility.INELIGIBLE
            }
            val process = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(process)
            // API 34 may still classify a new Activity process as cached in Application.onCreate.
            // Admit that provisional state only through the same two-second first-Activity bound.
            return if (process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED)
                Eligibility.IMPORTANCE else Eligibility.INELIGIBLE
        }
    }
}
