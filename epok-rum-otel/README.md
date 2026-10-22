# Epok Android RUM on OpenTelemetry

`epok-rum-otel` wraps OpenTelemetry Android 1.7.0 and is independent of replay.
The wrapper is experimental and emulator-validated; no real-device run has been
completed yet. A prior sample measurement found approximately **+320 KB download**;
this is not a new measurement here and varies with dependencies and shrinking.

## Install

Published on JitPack (current release: `0.2.1`). See the [root README](../README.md)
for replay configuration and its separate consent API.

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
    // Optional, independent masked wireframe replay:
    implementation("com.github.epok-cloud.epok-android:epok-replay:0.2.1")
}
```

The library builds with AGP 8.7.3, Gradle 8.9, Kotlin 2.1.21, JDK 17 and
Build Tools 35.0.0. Android 8.0 (API 26) or later is required. Consume through
Maven/Gradle metadata: an AAR alone does not carry dependencies or exclusions.

## Configure and connect consent

```kotlin
import dev.epok.rum.otel.EpokRum
import dev.epok.rum.otel.EpokRumConfig
import okhttp3.OkHttpClient

// Main thread, in the main process's Application.onCreate(), before any Activity:
EpokRum.start(this, EpokRumConfig(
    endpoint = "https://collector.example.com", // base URL, without /v1/traces
    ingestKey = "YOUR_PUBLIC_INGEST_KEY",
    serviceName = "your-android-app",
    serviceVersion = BuildConfig.VERSION_NAME,
))

// Wire each call to the corresponding explicit choice in your consent UI:
EpokRum.grantConsent()
EpokRum.withdrawConsent()
val state = EpokRum.consentState()

// Install both once on each OkHttp client to be instrumented:
val client = OkHttpClient.Builder()
    .addInterceptor(EpokRum.connectionInterceptor())
    .addNetworkInterceptor(EpokRum.networkInterceptor())
    .build()
```

There is no built-in collector or key. Transport is gzip OTLP/HTTP protobuf to
`<endpoint>/v1/traces` and `<endpoint>/v1/logs`, with `X-API-Key`. Use an ingest-only
key; any key shipped in an application is public. HTTPS is recommended. Cleartext
requires the app's Android network-security configuration. Internet and network-state
permissions are declared by the library. Collector redirects and retries are disabled.
The collector uses its own OkHttp client, so app-specific pinning, DNS, proxy and
authenticator settings are not inherited; Android Network Security Config still applies.

Call `start` once; subsequent configurations are ignored. `serviceVersion` defaults
to the installed package version. Do not initialize a second OTel Android agent in
the same process. Secondary-process calls do not collect and report `NOT_MAIN_PROCESS`.

Consent defaults to **denied** and is persisted. A denied launch creates no OTel SDK,
exporter or OTel buffer and resumes any interrupted purge. A saved grant initializes
asynchronously on the next launch. Interceptors pass through until initialization
finishes. States are `DENIED`, `INITIALIZING`, `GRANTED`, `GRANTED_PENDING_RESTART`,
`FAILED`, and `NOT_MAIN_PROCESS`. Consent methods require `start` first.

Withdrawal blocks new exports, cancels collector calls, persists denial and schedules
SDK shutdown and buffer deletion. Purge completion is asynchronous. Data already
received by the collector cannot be recalled. Persistence uses filesystem I/O on
the calling thread; timing is not guaranteed. After an initialization attempt and
withdrawal, granting again requires a process restart to resume collection. A normal
regrant reports `GRANTED_PENDING_RESTART`; initialization failures can remain `FAILED`.
Initialization failures use persisted backoff and a five-attempt cap. For developer
support, `EpokRum.resetInitFailures()` clears failure counters without granting consent
or retrying an already-attempted process. It is not a routine consent-sync operation.
If using replay too, connect both libraries' consent methods to the app's policy.

## Capture and privacy

Captured signals include Java/Kotlin uncaught crashes, foreground ANRs, Activity and
AndroidX Fragment lifecycle, slow/frozen drawing, sessions, network changes, configured
OkHttp traffic and eligible cold starts. Metrics and replay are not part of this module.
Crash delivery generally occurs on a later granted launch. ANR detection can take
about ten seconds. Frame instrumentation measures drawing, not every input/layout delay.

Before span/log export, including recovered records, the library removes URL queries,
fragments and credentials from `url.full` and `http.url`, drops `url.query`, carrier
attributes and installation IDs, and caps exception stacks at 16 KiB of UTF-8.
These filters also cover resource attributes, span events and links. Malformed URLs
are filtered conservatively.

Filtering happens at export. App-private OTel disk buffers can contain original
values until purged. URL paths, exception messages, log bodies and screen names are
not generally redacted; stack truncation is a size bound, not message redaction.
Request/response bodies and headers are not captured by default. An installation ID
may be created locally while granted, but is omitted from exports and cleared on
withdrawal. This is not a general-purpose sensitive-data detector.

Instrumented OkHttp requests propagate W3C trace context to every destination,
including redirects. There is no host allowlist option. Attach the adapters only to
clients whose destinations may receive that context. App requests continue while
consent is denied; an already-running request can retain previously injected context.

## Limits and dependency choices

Cold-start capture requires consent already granted at launch and an early `start`.
Mid-process grants cannot backfill it. On API 35+, cold Activity/launcher start data
is used when available. The fallback uses process importance and a two-second
first-Activity window: it can misclassify background starts and omit slow genuine
starts, biasing startup percentiles. Warm starts are not captured. The first Activity's
`Created` span can be missed because SDK initialization is asynchronous.

Native crashes, WebView traffic, HttpURLConnection, Compose navigation, custom metrics,
remote erasure and automatic release-stack symbolication are unsupported. Keep your
app's R8 mapping files for your own symbolication. Collector outages and full-buffer
pressure are not validated. Rejected batches can block a signal's disk queue until
expiry or eviction. Delivery and initialization/withdrawal timing are not guaranteed.

OTel Android is declared at 1.7.0 (1.7.0-alpha for alpha modules), with Java SDK/exporter
1.65.0. Dependencies use ordinary minimum versions, without strict constraints or
consumer force rules: OkHttp 4.12.0, Core KTX 1.16.0, Fragment 1.8.5, Lifecycle 2.8.7,
Kotlin stdlib 2.1.21 and coroutines 1.10.2. Exclusions on OTel dependency edges are
published in both POM and Gradle metadata. These older floors are a wrapper-tested
combination, not the upstream supported dependency set or a promise of compatibility
with every future version. A Java bridge isolates upstream Kotlin APIs; consumer
ProGuard rules preserve instrumentation discovery. The packaging exclusion in the
application configuration is still required.

Local publication and unit tests:

```sh
./gradlew --no-daemon :epok-replay:publishToMavenLocal :epok-rum-otel:publishToMavenLocal -x test
./gradlew --no-daemon :epok-rum-otel:testReleaseUnitTest
```

JitPack supplies `GROUP` and `VERSION`; local defaults are `dev.epok` and `0.2.0`.
