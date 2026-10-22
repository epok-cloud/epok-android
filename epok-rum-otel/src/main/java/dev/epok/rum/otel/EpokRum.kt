package dev.epok.rum.otel

import android.app.Application
import android.content.Context
import android.app.ActivityManager
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import okhttp3.Interceptor
import java.io.File
import java.net.URI

data class EpokRumConfig(
    val endpoint: String,
    val ingestKey: String,
    val serviceName: String,
    val serviceVersion: String? = null
) {
    init {
        val uri = URI(endpoint)
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null) { "Expected an HTTP(S) base endpoint" }
        require(ingestKey.isNotBlank() && ingestKey.none { it == '\r' || it == '\n' })
        require(serviceName.isNotBlank())
    }
    // Never expose the key through data-class logging.
    override fun toString() = "EpokRumConfig(serviceName=$serviceName)"
}

enum class ConsentState { DENIED, INITIALIZING, GRANTED, GRANTED_PENDING_RESTART, FAILED, NOT_MAIN_PROCESS }

/** One owner in the app's main process. Call start from Application.onCreate. */
object EpokRum {
    @Volatile private var controller: ConsentController? = null
    private var configuration: EpokRumConfig? = null
    private var startedApplication: Application? = null
    private var startFailed = false
    private var secondaryProcess = false

    @Synchronized
    fun start(application: Application, config: EpokRumConfig) {
        if (startedApplication != null) return
        startedApplication = application
        val main = isMainProcess(application)
        if (startFailed) return
        if (!main) {
            secondaryProcess = true
            Log.i("EpokRum", "Ignoring start outside the main application process")
            return
        }
        configuration = config
        try { controller = ConsentController(application) { OtelBridge(application, config) } }
        catch (error: Throwable) {
            startFailed = true
            Log.e("EpokRum", "Could not start RUM", error)
        }
    }

    internal fun isMainProcess(application: Application): Boolean = try {
        val name = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else {
            val manager = application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        checkNotNull(name) { "Missing process name" } == application.applicationInfo.processName
    } catch (error: Throwable) {
        Log.e("EpokRum", "Could not identify process; reporting FAILED without initializing RUM", error)
        startFailed = true
        true
    }

    @Synchronized fun grantConsent() {
        val app = checkNotNull(startedApplication) { "Call EpokRum.start first" }
        if (secondaryProcess) { Log.i("EpokRum", "Ignoring consent outside the main process"); return }
        try {
            controller?.let { it.grant(); return }
            // No SDK was constructed. Preserve the choice if storage has recovered.
            check(app.getSharedPreferences("epok-rum-otel-consent", 0).edit().putBoolean("granted", true).commit())
            ConsentStorage.allow(app)
            startFailed = true
        } catch (error: Throwable) { startFailed = true; Log.e("EpokRum", "Could not grant consent", error) }
    }
    @Synchronized fun withdrawConsent() {
        val app = checkNotNull(startedApplication) { "Call EpokRum.start first" }
        if (secondaryProcess) { Log.i("EpokRum", "Ignoring consent outside the main process"); return }
        try {
            val current = controller
            if (current != null) current.withdraw()
            else {
                ConsentStorage.deny(app)
                Cleanup.execute { ConsentStorage.purge(app) }
                startFailed = false
            }
        } catch (error: Throwable) { Log.e("EpokRum", "Could not withdraw consent", error) }
    }
    /** Developer/support escape hatch. Does not grant consent or reinitialize this process. */
    @Synchronized fun resetInitFailures() {
        val app = checkNotNull(startedApplication) { "Call EpokRum.start first" }
        if (secondaryProcess) return
        try {
            val current = controller
            if (current != null) current.resetInitFailures()
            else InitHealth.clearFailures(app.getSharedPreferences("epok-rum-otel-consent", 0))
        } catch (error: Throwable) { Log.e("EpokRum", "Could not reset initialization health", error) }
    }
    fun consentState(): ConsentState = if (secondaryProcess) ConsentState.NOT_MAIN_PROCESS
        else controller?.state() ?: if (startFailed) ConsentState.FAILED else ConsentState.DENIED

    /** Install once with OkHttpClient.Builder.addInterceptor. Safe before start and grant. */
    fun connectionInterceptor(): Interceptor = HostInterceptors.lookup { lookupInterceptor { it.connectionInterceptor() } }
    /** Install once with OkHttpClient.Builder.addNetworkInterceptor (also checks redirects). */
    fun networkInterceptor(): Interceptor = HostInterceptors.lookup { lookupInterceptor { it.networkInterceptor() } }
    private fun lookupInterceptor(lookup: (RumRuntime) -> Interceptor): Interceptor? = try {
        controller?.activeRuntime()?.let(lookup)
    } catch (error: VirtualMachineError) { throw error }
    catch (error: Throwable) {
        Log.e("EpokRum", "Instrumentation lookup failed; continuing host request", error)
        null
    }
}

internal object Cleanup : Executor {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "epok-rum-cleanup").apply { isDaemon = true } }
    override fun execute(command: Runnable) = worker.execute(command)
}

internal interface RumRuntime {
    fun initialize() {}
    fun enable() {}
    /** Must only close admission/cancel sends; never await initialization or shutdown. */
    fun deactivate() {}
    fun recordColdStart(startNanos: Long, endNanos: Long, activity: String) {}
    fun shutdown()
    fun connectionInterceptor(): Interceptor
    fun networkInterceptor(): Interceptor
}

/** No OTel types or initializers in this consent boundary. */
internal class ConsentController(
    private val application: Application,
    private val executor: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "epok-rum-init").apply { isDaemon = true }
    },
    private val cleanup: Executor = Cleanup,
    private val now: () -> Long = System::currentTimeMillis,
    private val version: Long = ConsentStorage.versionCode(application),
    private val prefs: android.content.SharedPreferences = application.getSharedPreferences("epok-rum-otel-consent", Context.MODE_PRIVATE),
    private val factory: (Boolean) -> RumRuntime
) {
    @Volatile private var runtime: RumRuntime? = null
    private var initialized = false
    @Volatile private var consent = ConsentState.DENIED
    private var coldStart: ColdStart? = null
    private var firstFrame: Triple<Long, Long, String>? = null
    private var purgeComplete = java.util.concurrent.CountDownLatch(0)

    // Serializes durable health/choice writes without holding the state/first-frame monitor.
    private val persistence = Any()
    private val choice = Any() // Initialization never holds this lock.
    private var crashGuard: InitCrashGuard? = null

    init {
        // A durable withdrawal takes precedence over an older granted value if the process
        // died before the asynchronous mirror write completed.
        if (ConsentStorage.denied(application)) prefs.edit().putBoolean("granted", false).apply()
        synchronized(persistence) {
            if (prefs.getLong("failure_version", version) != version ||
                prefs.getLong("init_version", version) != version) clearFailures()
            decayFailures()
            if (prefs.getBoolean("init_in_progress", false)) {
                if (prefs.getBoolean("granted", false) && InitHealth.countPreviousDeath(application, prefs.getInt("init_pid", -1))) recordFailure()
                else InitHealth.clearMarker(prefs)
            }
        }
        if (prefs.getBoolean("granted", false)) activate(true)
        else synchronized(this) { stopAndPurge() }
    }

    fun state() = consent
    fun activeRuntime() = runtime.takeIf { consent == ConsentState.GRANTED }

    fun grant() {
        try {
            synchronized(persistence) {
                synchronized(choice) {
                    val accepted = synchronized(this) {
                        consent in setOf(ConsentState.DENIED, ConsentState.FAILED)
                    }
                    if (!accepted) return
                    check(prefs.edit().putBoolean("granted", true).commit())
                    ConsentStorage.allow(application)
                    if (!retryAllowed()) { synchronized(this) { consent = ConsentState.FAILED }; return }
                    synchronized(this) {
                        if (initialized) {
                            consent = if (consent == ConsentState.FAILED) ConsentState.FAILED else ConsentState.GRANTED_PENDING_RESTART
                            return
                        }
                    }
                    activate(false)
                }
            }
        } catch (error: Throwable) { consent = ConsentState.FAILED; Log.e("EpokRum", "Consent storage failed", error) }
    }

    fun resetInitFailures() = synchronized(persistence) {
        // Preserve any active attempt marker so a crash immediately after reset is still recoverable.
        InitHealth.clearFailures(prefs)
    }

    private fun decayFailures() {
        val last = prefs.getLong("last_failure_at", 0)
        if (prefs.contains("last_failure_at") && now() > last && now() - last > 7 * 86_400_000L)
            InitHealth.clearFailures(prefs)
    }
    private fun retryAllowed(): Boolean {
        if (prefs.getLong("failure_version", version) != version) clearFailures()
        decayFailures()
        val currentTime = now()
        val lastFailure = prefs.getLong("last_failure_at", 0)
        if (lastFailure > currentTime) check(prefs.edit().putLong("last_failure_at", currentTime).commit())
        val failures = prefs.getInt("init_failures", 0)
        val delay = when { failures < 2 -> 0L; failures == 2 -> 3_600_000L; else -> 86_400_000L }
        return failures < 5 && (failures == 0 || currentTime - prefs.getLong("last_failure_at", 0) >= delay)
    }
    private fun clearFailures() {
        check(prefs.edit().remove("init_failures").remove("last_failure_at").remove("failure_version")
            .remove("init_in_progress").remove("init_version").remove("init_pid").commit()) { "Could not clear initialization health" }
    }
    private fun activate(cold: Boolean) {
        synchronized(persistence) {
            if (!retryAllowed()) { consent = ConsentState.FAILED; return }
            synchronized(this) {
                if (initialized) return
                initialized = true // Partial SDK construction consumes this process's single attempt.
                consent = ConsentState.INITIALIZING
                if (cold) coldStart = ColdStart(application) { start, end, activity ->
                    synchronized(this) {
                        if (consent == ConsentState.GRANTED) runtime?.recordColdStart(start, end, activity)
                        else if (consent == ConsentState.INITIALIZING) firstFrame = Triple(start, end, activity)
                    }
                }
            }
        }
        val priorPurge = purgeComplete
        try {
            executor.execute {
                var guard: InitCrashGuard? = null
                try {
                    priorPurge.await()
                    synchronized(persistence) {
                        if (consent != ConsentState.INITIALIZING) return@execute
                        check(prefs.edit().putBoolean("init_in_progress", true).putLong("init_version", version)
                            .putInt("init_pid", Process.myPid()).putLong("failure_version", version).commit()) { "Could not persist init attempt" }
                        guard = InitCrashGuard(prefs)
                        synchronized(this) { crashGuard = guard }
                    }
                    if (consent != ConsentState.INITIALIZING) return@execute
                    val created = factory(cold)
                    val accepted = synchronized(this) {
                        if (consent == ConsentState.INITIALIZING) { runtime = created; true } else false
                    }
                    if (!accepted) { created.deactivate(); created.shutdown(); return@execute }
                    created.initialize()
                    synchronized(persistence) {
                        if (consent == ConsentState.INITIALIZING) clearFailures()
                        synchronized(this) {
                            if (consent == ConsentState.INITIALIZING) {
                                created.enable()
                                consent = ConsentState.GRANTED
                                firstFrame?.let { created.recordColdStart(it.first, it.second, it.third) }
                                firstFrame = null
                            }
                        }
                    }
                } catch (error: Throwable) { fail(error) }
                finally { guard?.close() }
            }
        } catch (error: Throwable) { fail(error) }
    }

    private fun fail(error: Throwable) {
        val accepted = synchronized(this) {
            if (consent != ConsentState.INITIALIZING && consent != ConsentState.GRANTED) false
            else { consent = ConsentState.FAILED; runtime?.deactivate(); true }
        }
        if (!accepted) return
        Log.e("EpokRum", "RUM initialization failed; retry subject to persistent backoff", error)
        // User consent is independent of operational health.
        try {
            synchronized(persistence) { recordFailure() }
        } finally { stopAndPurge() }
    }

    private fun recordFailure() {
        check(prefs.edit().putInt("init_failures", (prefs.getInt("init_failures", 0) + 1).coerceAtMost(5))
            .putLong("last_failure_at", now()).putLong("failure_version", version)
            .remove("init_in_progress").remove("init_version").remove("init_pid").commit()) { "Could not persist initialization failure" }
    }

    fun withdraw() {
        synchronized(choice) {
            // Decide the fence before any persistence; initialization cannot reopen it.
            synchronized(this) {
                consent = ConsentState.DENIED
                stopAndPurge()
                crashGuard?.close()
            }
            ConsentStorage.deny(application)
        }
    }

    @Synchronized private fun stopAndPurge() {
        coldStart?.close()
        coldStart = null
        firstFrame = null
        val previous = runtime
        runtime = null
        previous?.deactivate()
        val complete = java.util.concurrent.CountDownLatch(1)
        purgeComplete = complete
        cleanup.execute {
            try { previous?.shutdown() }
            catch (error: Throwable) { Log.e("EpokRum", "RUM shutdown failed", error) }
            finally { try { ConsentStorage.purge(application) } finally { complete.countDown() } }
        }
    }
}

internal object InitHealth {
    fun countPreviousDeath(app: Application, pid: Int): Boolean {
        if (Build.VERSION.SDK_INT < 30) return true
        if (pid <= 0) return false
        return try {
            val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exit = manager.getHistoricalProcessExitReasons(app.packageName, pid, 1).firstOrNull()
            exit?.pid == pid && exit.reason in setOf(android.app.ApplicationExitInfo.REASON_CRASH,
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, android.app.ApplicationExitInfo.REASON_ANR)
        } catch (error: RuntimeException) { Log.w("EpokRum", "Exit reason unavailable; not counting init failure", error); false }
    }
    fun clearMarker(prefs: android.content.SharedPreferences) {
        check(prefs.edit().remove("init_in_progress").remove("init_version").remove("init_pid").commit())
    }
    fun clearFailures(prefs: android.content.SharedPreferences) {
        check(prefs.edit().remove("init_failures").remove("last_failure_at").remove("failure_version").commit())
    }
}

/** May remain nested in upstream's crash handler; close neutralizes it without replacing that handler. */
internal class InitCrashGuard(
    private val prefs: android.content.SharedPreferences,
    private val kill: (Int) -> Unit = Process::killProcess,
    private val exit: (Int) -> Unit = { kotlin.system.exitProcess(it) }
) : Thread.UncaughtExceptionHandler {
    private val previous = Thread.getDefaultUncaughtExceptionHandler()
    @Volatile private var active = true
    init { Thread.setDefaultUncaughtExceptionHandler(this) }
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            if (active && prefs.getBoolean("init_in_progress", false) && !belongsToRum(error)) InitHealth.clearMarker(prefs)
        } catch (failure: Throwable) { Log.e("EpokRum", "Could not clear host-crash marker", failure) }
        finally {
            if (previous != null) previous.uncaughtException(thread, error)
            else {
                error.printStackTrace(System.err)
                try { kill(Process.myPid()) } finally { exit(10) }
            }
        }
    }
    fun close() {
        // Thread has no compare-and-set for its global handler. A single volatile write
        // neutralizes this wrapper without racing another library installing a handler.
        active = false
    }
    private fun belongsToRum(error: Throwable): Boolean {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current = error
        // The deepest cause identifies the failure, not an outer telemetry wrapper.
        seen.add(current)
        while (current.cause != null && seen.add(current.cause!!)) current = current.cause!!
        val origin = current.stackTrace.firstOrNull {
            val name = it.className
            !name.startsWith("java.") && !name.startsWith("javax.") && !name.startsWith("jdk.") &&
                !name.startsWith("sun.") && !name.startsWith("android.") && !name.startsWith("dalvik.") &&
                !name.startsWith("io.opentelemetry.instrumentation.okhttp.") &&
                !name.startsWith("dev.epok.rum.otel.HostInterceptors") &&
                !name.startsWith("io.opentelemetry.instrumentation.library.okhttp.") &&
                !name.startsWith("io.opentelemetry.android.instrumentation.okhttp.") &&
                !(name.startsWith("dev.epok.rum.otel.EpokRum") && it.methodName.contains("intercept", ignoreCase = true)) &&
                !(name.startsWith("dev.epok.rum.otel.OtelBridge") && it.methodName.contains("intercept", ignoreCase = true))
        } ?: return false
        return origin.className.startsWith("dev.epok.rum.otel.") || origin.className.startsWith("io.opentelemetry.")
    }
}

internal object ConsentStorage {
    fun versionCode(application: Application): Long = try {
        val info = application.packageManager.getPackageInfo(application.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (_: Exception) { 0L }

    private fun withdrawal(application: Application) =
        application.getSharedPreferences("epok-rum-otel-withdrawal", Context.MODE_PRIVATE)

    fun denied(application: Application) = withdrawal(application).getBoolean("denied", false)

    fun allow(application: Application) {
        // Called only after the grant has been committed; a crash between writes stays denied.
        check(withdrawal(application).edit().putBoolean("denied", false).commit())
    }

    fun deny(application: Application) {
        try {
            // Separate disk file/lock: an init-health fsync must not block the consent fence.
            check(withdrawal(application).edit().putBoolean("denied", true).commit())
        } catch (error: Throwable) { Log.e("EpokRum", "Could not persist denied consent", error) }
        try {
            application.getSharedPreferences("epok-rum-otel-consent", Context.MODE_PRIVATE)
                .edit().putBoolean("granted", false).remove("init_in_progress")
                .remove("init_version").remove("init_pid").apply()
        } catch (error: Throwable) { Log.e("EpokRum", "Could not mirror denied consent", error) }
    }

    fun purge(application: Application) {
        try {
            val buffer = File(application.cacheDir, "opentelemetry")
            if (buffer.exists() && !buffer.deleteRecursively()) Log.e("EpokRum", "Could not purge OTel buffer")
        } catch (error: Throwable) { Log.e("EpokRum", "Could not purge OTel buffer", error) }
        try {
            if (!application.getSharedPreferences("opentelemetry-android", Context.MODE_PRIVATE)
                    .edit().remove("app.installation.id").commit()) Log.e("EpokRum", "Could not clear installation id")
        } catch (error: Throwable) { Log.e("EpokRum", "Could not clear installation id", error) }
    }
}
