package dev.epok.rum.sample

import dev.epok.replay.EpokReplay
import dev.epok.replay.EpokReplayConfig

import android.app.Application
import android.os.Process
import dev.epok.rum.ManualConnectivity
import dev.epok.rum.Rum
import dev.epok.rum.RumConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Public instrumentation hook; all HTTP destinations in this sample resolve to loopback. */
class SampleApplication : Application() {
    lateinit var rum: Rum
        private set
    lateinit var network: ManualConnectivity
        private set
    private val consentLock = Any()
    lateinit var replay: EpokReplay
        private set
    private lateinit var client: OkHttpClient
    private val prefs by lazy { getSharedPreferences("poc-controls", MODE_PRIVATE) }
    private var originPort = 4319
    @Volatile var lastCall = "none"
        private set

    override fun onCreate() {
        super.onCreate()
        replay = EpokReplay(this, EpokReplayConfig(
            ingestKey = BuildConfig.EPOK_REPLAY_INGEST_KEY,
            endpoint = BuildConfig.EPOK_REPLAY_HOST.trimEnd('/').removeSuffix("/v1/rum/mobile/android"),
        ))
        buildRuntime()
        writeStatus("startup.json")
    }

    fun configure(endpoint: String, originPort: Int) {
        withdrawConsent()
        // Explicit instrumentation fixture reset, never an SDK counter-reset operation.
        check(File(noBackupFilesDir, "epok-rum").let { !it.exists() || it.deleteRecursively() })
        prefs.edit().putString("endpoint", endpoint).putInt("originPort", originPort).putBoolean("online", true).commit()
        buildRuntime()
    }

    private fun buildRuntime() {
        originPort = prefs.getInt("originPort", 4319)
        network = ManualConnectivity(prefs.getBoolean("online", true))
        replay.setOnline(prefs.getBoolean("online", true))
        rum = Rum.create(this, RumConfig(
            endpoint = prefs.getString("endpoint", "http://127.0.0.1:4318")!!,
            publicIngestKey = "public-poc-ingest-only", accountId = "poc-account", projectId = "poc-project",
            serviceName = "epok-android-sample", allowedOrigins = setOf("http://api.getepok.dev:$originPort"),
            flushIntervalMs = 100, retryBaseMs = 100
        ), network)
        client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (hostname !in setOf("api.getepok.dev", "api.evil-getepok.dev")) throw UnknownHostException("Sample only supports its two loopback aliases")
                    return listOf(InetAddress.getByName("127.0.0.1"))
                }
            }).addNetworkInterceptor(rum.networkInterceptor()).callTimeout(3, TimeUnit.SECONDS).build()
    }

    fun grantConsent() = synchronized(consentLock) {
        rum.grantConsent()
        if (rum.snapshot().consent && BuildConfig.EPOK_REPLAY_ENABLED) replay.grant()
    }

    fun denyConsent() = withdrawConsent()

    fun withdrawConsent() = synchronized(consentLock) {
        try { replay.withdraw() } finally { rum.withdrawConsent() }
        writeStatus()
    }

    fun setOnline(online: Boolean) {
        prefs.edit().putBoolean("online", online).commit()
        network.setOnline(online)
        synchronized(consentLock) { replay.setOnline(online) }
    }

    suspend fun fire(allowed: Boolean): Int = withContext(Dispatchers.IO) {
        val host = if (allowed) "api.getepok.dev" else "api.evil-getepok.dev"
        try {
            client.newCall(Request.Builder().url("http://$host:$originPort/api/users/42?token=sample-secret#private").build())
                .execute().use { response ->
                    lastCall = (if (allowed) "allowed" else "lookalike") + ":${response.code}"
                    response.code
                }
        } catch (_: Exception) { lastCall = "host_request_failed"; 0 }
    }

    fun enqueue() { rum.recordHttp("https://app.example/api/users/42?token=sample-secret#private") }

    fun status(): JSONObject = rum.snapshot().json().put("pid", Process.myPid()).put("lastCall", lastCall).put("replay", replay.status().put("enabled", BuildConfig.EPOK_REPLAY_ENABLED))
    fun writeStatus(name: String = "status.json") { File(filesDir, name).writeText(status().toString()) }

    fun enqueueThenKill() {
        setOnline(false)
        enqueue()
        writeStatus("before-kill.json")
        // No close/shutdown callback: the queue must already have reached durable storage.
        Process.killProcess(Process.myPid())
    }
}
