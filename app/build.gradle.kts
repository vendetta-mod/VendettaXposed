import android.annotation.SuppressLint

plugins {
    id("com.android.application")
    id("kotlin-android")
    kotlin("plugin.serialization") version "1.8.0"
}

android {
    compileSdk = 33

    defaultConfig {
        applicationId = "com.vendetta.xposed"
        minSdk = 24
        targetSdk = 33
        versionCode = 8
        versionName = "1.1.5"
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    namespace = "com.vendetta.xposed"
}

dependencies {
    implementation("androidx.core:core:1.10.1")
    compileOnly("de.robv.android.xposed:api:82")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.1")
}
