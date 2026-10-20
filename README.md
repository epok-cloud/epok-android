# Epok session replay for Android

Masked wireframe session replay for Android apps, sent to [Epok](https://getepok.dev).
Replays play in the same Epok player as browser sessions, next to your traces and errors.

Capture uses PostHog's open-source Android recorder (MIT), pinned to one tested version
(3.72.3) and configured so that nothing is sent to PostHog.

## Install

Android 8.0 (API 26) or later.

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
dependencies {
    implementation("com.github.jayswamidass.epok-android:epok-replay-android:0.1.0")
}
```

## Use

```kotlin
import dev.epok.replay.EpokReplay
import dev.epok.replay.EpokReplayConfig

class App : Application() {
    lateinit var replay: EpokReplay

    override fun onCreate() {
        super.onCreate()
        // Constructs nothing yet: no capture, no queue, no network.
        replay = EpokReplay(this, EpokReplayConfig(
            ingestKey = "epk_…",                      // restricted ingest-only key
            endpoint = "https://ingest.getepok.dev",
        ))
    }
}

// From your consent UI, on a background thread (setup does disk I/O):
app.replay.grant()     // start masked wireframe capture
app.replay.withdraw()  // stop capture and erase anything still queued
```

## Privacy

- Wireframes only: no screenshots, no video.
- Text inputs and images are always masked.
- Logs, network bodies and analytics events are not captured.
- Nothing is created, recorded, queued or sent until `grant()`. `withdraw()` stops capture and
  erases anything still queued on the device.
- The HTTP client refuses every URL except Epok's replay route.

The key ships inside your app, so treat it as public and use an ingest-only key.

Full guide: https://getepok.dev/docs/install/mobile-replay

## Repository layout

- `epok-replay` — the replay library.
- `epok-rum` — the Epok RUM prototype (consent, durable queue, HTTP capture); not yet released.
- `sample` — a sample app that uses the library.

## Licence

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
