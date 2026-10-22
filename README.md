# Epok Android libraries

Android libraries for sending telemetry to Epok. Android 8.0 (API 26) or later.

- `epok-rum-otel`: consent-controlled RUM using OpenTelemetry Android 1.7.0.
- `epok-replay`: masked wireframe session replay using PostHog Android 3.72.3.

## Install with JitPack

The coordinates below are for the planned `0.2.0` release. They become available
once that tag is published and JitPack builds it.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
android {
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    packaging.resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
}
dependencies {
    implementation("com.github.epok-cloud.epok-android:epok-rum-otel:0.2.1")
    implementation("com.github.epok-cloud.epok-android:epok-replay:0.2.1")
}
```

Choose either library or both. Version 0.1.0 used the replay artifact name
`epok-replay-android`; the 0.2.0 coordinate uses `epok-replay`.

## RUM configuration and consent

```kotlin
import dev.epok.rum.otel.EpokRum
import dev.epok.rum.otel.EpokRumConfig
import okhttp3.OkHttpClient

// In the main-process Application.onCreate(), before the first Activity:
EpokRum.start(this, EpokRumConfig(
    endpoint = "https://collector.example.com", // base URL, without /v1/traces
    ingestKey = "YOUR_PUBLIC_INGEST_KEY",
    serviceName = "your-android-app",
    serviceVersion = BuildConfig.VERSION_NAME,
))

// Connect these calls to explicit choices in your consent UI:
EpokRum.grantConsent()
EpokRum.withdrawConsent()
val state = EpokRum.consentState()

// Install both adapters once on each OkHttp client you want to instrument:
val client = OkHttpClient.Builder()
    .addInterceptor(EpokRum.connectionInterceptor())
    .addNetworkInterceptor(EpokRum.networkInterceptor())
    .build()
```

Consent defaults to denied and is persisted. Before a grant, no OTel SDK,
exporter or buffer is created. A previously granted choice starts asynchronous
initialization on the next launch. Withdrawal blocks new exports, cancels collector
calls and asynchronously purges local buffers. It cannot recall data already sent.
After initialization and withdrawal, granting again requires a process restart to
resume capture (`GRANTED_PENDING_RESTART`). RUM and replay have separate consent APIs;
connect both to your app's consent policy if you use both.

RUM sends gzip OTLP/HTTP protobuf to the configured base URL's `/v1/traces` and
`/v1/logs`, authenticated with `X-API-Key`. Use an ingest-only key: any key shipped
in an app is public. No endpoint or key is built into the RUM library.

Captured signals include Java/Kotlin uncaught crashes, foreground ANRs, Activity
and Fragment lifecycle, slow/frozen drawing, sessions, network changes, configured
OkHttp traffic, and eligible cold starts. Metrics and replay are not part of this
RUM module. See the [RUM guide](epok-rum-otel/README.md) for setup and limitations.

## Replay configuration and consent

```kotlin
import dev.epok.replay.EpokReplay
import dev.epok.replay.EpokReplayConfig

val replay = EpokReplay(application, EpokReplayConfig(
    ingestKey = "YOUR_PUBLIC_INGEST_KEY",
    endpoint = "https://collector.example.com", // Epok ingest base URL
))

// On a background thread; serialize these calls in the app:
replay.grant()
replay.withdraw()
```

Replay starts denied on every process launch. Construction purges old queued replay;
`grant()` starts recording and `withdraw()` stops recording and erases queued data.
Replay uses Epok's `/v1/rum/mobile/android/s/` route. Its HTTP client rejects other
routes. It records wireframes with text inputs and images masked; screenshots,
video, logcat, network bodies and analytics events are not captured. Nothing is
sent to PostHog.

## Privacy and limits

RUM removes URL queries, fragments and credentials before export, drops carrier
attributes and installation IDs, and caps exported stack traces. Filtering occurs
at export: app-private OTel disk buffers may contain the original values until
purged. URL paths, exception messages, log bodies and screen names are not generally
redacted. Request/response bodies and headers are not captured by default.
Instrumented OkHttp requests propagate trace context to every destination,
including redirects; attach the adapters only to appropriate clients.

The RUM wrapper is experimental and emulator-validated; no real-device run has
been completed yet. Prior sample measurement found approximately **+320 KB download**
size; the actual increase depends on your app, dependencies and shrinking settings.
This is not a new measurement from this repository. Native crashes, WebView traffic,
Compose navigation, remote erasure and automatic release-stack symbolication are
not supported. Delivery, initialization timing and withdrawal timing are not
guaranteed on every device.

## Repository layout

- `epok-rum-otel` — OpenTelemetry RUM library and unit tests.
- `epok-replay` — replay library.
- `epok-rum` — earlier RUM prototype; not published by this JitPack configuration.
- `sample` — existing prototype/replay sample; not an OTel integration example.

## Licence

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
