plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

// Published coordinates: dev.epok:epok-replay-android:<version>.
// `./gradlew :epok-replay:publishToMavenLocal` (or a configured repository).
// JitPack sets GROUP/VERSION from the repository and tag; local builds keep dev.epok.
group = providers.environmentVariable("GROUP").orElse("dev.epok").get()
version = providers.environmentVariable("VERSION").orElse(providers.gradleProperty("epokReplayVersion").orElse("0.1.0")).get()

android {
    namespace = "dev.epok.replay"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    // EpokReplay seeds a PostHog-internal preference key to start replay without
    // contacting PostHog (see EpokReplay.kt). It is only valid for this exact
    // SDK version: pin strictly so an app's newer PostHog fails resolution
    // loudly instead of silently never recording.
    api("com.posthog:posthog-android") { version { strictly("3.72.3") } }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.21")
    testImplementation("junit:junit:4.13.2")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "epok-replay-android"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Epok session replay for Android")
                description.set("Masked wireframe session replay for Android apps, sent to Epok.")
                url.set("https://getepok.dev/docs/install/mobile-replay")
            }
        }
    }
}
