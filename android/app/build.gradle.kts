import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.fayor.justtalk"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fayor.justtalk"
        minSdk = 29
        // 35, not newer: newer targets require a runtime permission for local network access
        targetSdk = 35
        versionCode = 25
        versionName = "0.17.6 Beta"
        // Yandex MapKit key lives in local.properties (MAPKIT_API_KEY=...), never in the sources
        val local = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
        buildConfigField("String", "MAPKIT_API_KEY", "\"${local.getProperty("MAPKIT_API_KEY", "")}\"")
        // MapKit ships native code for every CPU; phones are ARM, so the APK keeps only those
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    val serverProperties = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }

    buildTypes {
        // Set JT_SERVER in local.properties for an accessible server.
        debug {
            buildConfigField("String", "SERVER", "\"${serverProperties.getProperty("JT_SERVER", "http://10.0.2.2:8000")}\"")
        }
        release {
            buildConfigField("String", "SERVER", "\"${serverProperties.getProperty("JT_SERVER", "http://127.0.0.1:8000")}\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // signed with the local debug key: fine for friends' phones, not for Play
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // video circles: camera preview and recording
    val camerax = "1.6.2"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // 0.11: spots map
    implementation("com.yandex.android:maps.mobile:4.45.0-lite")
    // MapKit drags in an old Fragment that breaks permission requests; a fresh one wins
    implementation("androidx.fragment:fragment:1.8.9")
    // 0.15: «Смотреть вместе» — own videos from the server
    val media3 = "1.8.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
}
