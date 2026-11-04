// build.gradle.kts (Module: app)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.volumescroll"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.volumescroll"
        minSdk = 34
        targetSdk = 35
        versionCode = 6
        versionName = "4.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
}

/*
 * Proje düzeyindeki build.gradle.kts (Project) içinde şu eklentiler tanımlı olmalı:
 *
 * plugins {
 *     id("com.android.application") version "8.7.3" apply false
 *     id("org.jetbrains.kotlin.android") version "2.0.21" apply false
 *     id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
 * }
 */
