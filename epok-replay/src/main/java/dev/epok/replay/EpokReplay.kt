package dev.epok.replay

import android.app.Application
import com.posthog.PostHogInterface
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.PostHogMemoryPreferences
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Where replay goes. [ingestKey] must be an ingest-ONLY Epok key (it ships in
 * the app binary). [endpoint] is the Epok ingest origin. */
data class EpokReplayConfig(
    val ingestKey: String,
    val endpoint: String = "https://ingest.getepok.dev",
) {
    init {
        require(ingestKey.isNotBlank()) { "EpokReplayConfig.ingestKey is required" }
        require(endpoint.startsWith("https://") || endpoint.startsWith("http://")) { "endpoint must be an http(s) URL" }
    }
    /** PostHog appends "/s/" to its host: the Epok mobile replay route. */
    internal val host: String get() = endpoint.trimEnd('/') + "/v1/rum/mobile/android"
}

/** Epok session replay for Android: PostHog's capture SDK (pinned 3.72.3) sending
 * ONLY masked wireframe snapshots ONLY to Epok. Nothing is constructed, captured,
 * queued or sent until [grant]; [withdraw] stops capture and erases queued data.
 *
 * Call grant/withdraw from a background thread, serialized by the app (the
 * SDK's setup and shutdown do I/O). One instance per process.
 *
 * Privacy posture (not configurable): text inputs and images are masked,
 * screenshots are off, logcat is not captured, only `$snapshot` events leave the
 * device, and the HTTP client refuses every URL except Epok's replay route.
 */
class EpokReplay(private val application: Application, private val config: EpokReplayConfig) {
    private val root = File(application.noBackupFilesDir, "epok-replay")
    @Volatile private var sdk: PostHogInterface? = null
    private var http: OkHttpClient? = null
    private var disk: ReplayConsentStore? = null
    @Volatile private var allowed = false
    @Volatile private var online = true
    @Volatile private var failure: String? = null

    init {
        // Epok's RUM model denies consent on every process start. Old replay
        // queues must not silently upload when a later process grants consent.
        check(!root.exists() || root.deleteRecursively()) { "Replay startup purge failed" }
    }

    fun grant() {
        if (allowed) return
        val generation = File(root, UUID.randomUUID().toString())
        val queueStore = ReplayConsentStore(generation)
        // A gate belongs to ONE SDK generation. A future grant cannot reopen it.
        val gate = java.util.concurrent.atomic.AtomicBoolean(true)
        val client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                if (!gate.get() || !online) throw IOException("Replay upload gated")
                val request = chain.request()
                if (request.url.toString() != config.host + "/s/") {
                    throw IOException("Only Epok replay ingestion is enabled")
                }
                chain.proceed(request)
            }.build()
        val config = PostHogAndroidConfig(
            projectToken = config.ingestKey,
            host = config.host,
        ).apply {
            // Setup itself can start SDK work: this object is created ONLY after
            // rum.grantConsent() succeeds. Never construct the SDK at app startup.
            // Pinned SDK seam: manual start still needs the project recording
            // flag. Seed its in-memory config instead of contacting PostHog's
            // /flags or CDN. This must be rechecked on SDK upgrades.
            cachePreferences = PostHogMemoryPreferences().apply {
                // PostHogPreferences.SESSION_REPLAY is `internal` in posthog 6.47.1
                // (posthog-android 3.72.3), so it cannot be referenced from here.
                // Its literal value is "sessionReplay" (verified via javap on the
                // shipped jar); use the literal directly. Recheck on SDK upgrades.
                setValue("sessionReplay", mapOf("endpoint" to "/s/"))
            }
            sessionReplay = false
            persistOptOut = false
            optOut = false
            remoteConfig = false
            preloadFeatureFlags = false
            surveys = false
            captureApplicationLifecycleEvents = false
            captureDeepLinks = false
            captureScreenViews = false
            capturePushNotificationSubscriptions = false
            capturePushNotificationOpened = false
            errorTrackingConfig.autoCapture = false
            sessionReplayConfig.screenshot = false
            sessionReplayConfig.maskAllTextInputs = true
            sessionReplayConfig.maskAllImages = true
            sessionReplayConfig.captureLogcat = false
            flushAt = 10
            maxBatchSize = 50
            maxQueueSize = 100
            flushIntervalSeconds = 2
            storagePrefix = File(generation, "analytics").absolutePath
            replayStoragePrefix = File(generation, "replay").absolutePath
            logsStoragePrefix = File(generation, "logs").absolutePath
            legacyStoragePrefix = File(generation, "legacy").absolutePath
            encryption = queueStore
            httpClient = client
            // Also prevent delayed capture callbacks from admitting more data.
            addBeforeSend { event -> if (gate.get() && event.event == "\$snapshot") event else null }
        }
        disk = queueStore
        http = client
        revokeGate = { gate.set(false) }
        try {
            sdk = PostHogAndroid.with(application, config)
            sdk!!.startSessionReplay(resumeCurrent = false)
            check(sdk!!.isSessionReplayActive()) { "PostHog replay did not start" }
            allowed = true
            failure = null
        } catch (error: Exception) {
            withdraw()
            failure = error.javaClass.simpleName
        }
    }

    private var revokeGate: () -> Unit = {}

    fun withdraw() {
        allowed = false
        revokeGate()
        // Close the HTTP gate BEFORE close(): upstream close() calls flush().
        http?.dispatcher?.cancelAll()
        try {
            sdk?.stopSessionReplay()
            sdk?.optOut()
            sdk?.close()
        } finally {
            disk?.revokeAndPurge()
            sdk = null
            http?.connectionPool?.evictAll()
            http = null
            disk = null
            check(!root.exists() || root.deleteRecursively()) { "Replay queue purge failed" }
        }
    }

    fun setOnline(value: Boolean) {
        online = value
        if (value) sdk?.flush() else http?.dispatcher?.cancelAll()
    }

    fun flush() { if (allowed) sdk?.flush() }

    fun status(): JSONObject = JSONObject()
        .put("consent", allowed)
        .put("sdkConstructed", sdk != null)
        .put("captureActive", sdk?.isSessionReplayActive() == true)
        .put("queuedBytes", root.walkTopDown().filter { it.isFile }.sumOf { it.length() })
        .put("failure", failure ?: JSONObject.NULL)
}
