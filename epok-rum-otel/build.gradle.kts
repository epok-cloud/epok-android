plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}
// JitPack supplies the repository group and tag; local builds use these defaults.
group = providers.environmentVariable("GROUP").orElse("dev.epok").get()
version = providers.environmentVariable("VERSION").orElse("0.2.0").get()
android {
    namespace = "dev.epok.rum.otel"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "EPOK_RUM_VERSION", "\"${project.version}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    publishing { singleVariant("release") { withSourcesJar() } }
}
dependencies {
    // Exclusions are scoped to upstream edges and published in POM and Gradle metadata.
    // Ordinary dependencies provide tested floors; consumer apps can resolve upward.
    listOf(
        "io.opentelemetry.android:android-agent:1.7.0",
        "io.opentelemetry.android:core:1.7.0-alpha",
        "io.opentelemetry.android:common:1.7.0-alpha",
        "io.opentelemetry.android:services:1.7.0-alpha",
        "io.opentelemetry.android:session:1.7.0",
        "io.opentelemetry.android.instrumentation:android-instrumentation:1.7.0",
        "io.opentelemetry.android.instrumentation:okhttp3-library:1.7.0-alpha",
        "io.opentelemetry:opentelemetry-sdk:1.65.0",
        "io.opentelemetry:opentelemetry-exporter-otlp:1.65.0"
    ).forEach { coordinate ->
        implementation(coordinate) {
            exclude(group = "androidx.core", module = "core")
            exclude(group = "androidx.core", module = "core-ktx")
            exclude(group = "androidx.fragment")
            exclude(group = "androidx.lifecycle")
            exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
            exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
            exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-android")
            exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
            exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
            exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
            exclude(group = "com.squareup.okhttp3")
        }
    }
    api("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.65.0")
}
publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "epok-rum-otel"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Epok RUM for Android")
                description.set("Consent-controlled Android RUM using OpenTelemetry.")
                url.set("https://github.com/jayswamidass/epok-android")
            }
        }
    }
}
