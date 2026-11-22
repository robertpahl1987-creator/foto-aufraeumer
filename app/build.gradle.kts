plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.fotoaufraeumer.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.fotoaufraeumer.app"
        minSdk = 30          // Android 11+
        targetSdk = 34
        // Wird von GitHub bei jedem Bau hochgezählt (für automatische Updates)
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
        versionCode = 100 + run
        versionName = "1.2.$run"
    }

    // Fester Schlüssel, damit Updates sich ohne Deinstallation installieren lassen
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("io.coil-kt:coil-video:2.7.0")
    // Erkennung auf dem Handy (offline): Texte und Bildinhalte
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Kurzvideo-Erstellung
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
}
