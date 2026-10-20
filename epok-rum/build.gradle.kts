plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
val robolectricRuntimeSdk by configurations.creating { isCanBeConsumed = false; isTransitive = false }
val robolectricCompileSdk by configurations.creating { isCanBeConsumed = false; isTransitive = false }
val stageRobolectricSdks by tasks.registering(Copy::class) {
    from(robolectricRuntimeSdk, robolectricCompileSdk)
    into(layout.buildDirectory.dir("robolectric-sdks"))
}
android {
    namespace = "dev.epok.rum"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.dependsOn(stageRobolectricSdks)
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-sdks").get().asFile.absolutePath)
        }
    }
}
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.21")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    robolectricRuntimeSdk("org.robolectric:android-all-instrumented:9-robolectric-4913185-2-i7")
    robolectricCompileSdk("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
}
