plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val replayEnabled = providers.gradleProperty("epokReplayEnabled").orElse("false")
val replayHost = providers.gradleProperty("epokReplayHost").orElse("http://127.0.0.1:4320/v1/rum/mobile/android")
val replayKey = providers.environmentVariable("EPOK_REPLAY_INGEST_KEY").orElse("epok-fixture-ingest-key")
android {
    buildFeatures { buildConfig = true }
    namespace = "dev.epok.rum.sample"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "dev.epok.rum.sample"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("boolean", "EPOK_REPLAY_ENABLED", replayEnabled.get().toBoolean().toString())
        buildConfigField("String", "EPOK_REPLAY_HOST", quoted(replayHost.get()))
        buildConfigField("String", "EPOK_REPLAY_INGEST_KEY", quoted(replayKey.get()))
        ndk { abiFilters += "x86_64" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":epok-rum"))
    implementation(project(":epok-replay"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
