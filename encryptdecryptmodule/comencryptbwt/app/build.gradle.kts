// build.gradle.kts (app)

import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Función para obtener el SHA corto del commit actual (opcional)
fun getGitShaShort(): String {
    return try {
        val stdout = ByteArrayOutputStream()
        project.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            standardOutput = stdout
        }
        stdout.toString().trim()
    } catch (e: Exception) {
        "unknown"
    }
}

val currentGitSha = getGitShaShort()

android {
    namespace = "com.encrypt.bwt"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.encrypt.bwt"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Inyectar la variable GIT SHA en BuildConfig
        buildConfigField("String", "COMMIT_SHA", "\"$currentGitSha\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Habilitar BuildConfig, Compose y ViewBinding
    buildFeatures {
        buildConfig = true
        compose = true
        viewBinding = true
    }

    // Evitar metadatos de dependencias (para builds reproducibles, etc.)
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Config de Compose
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.3"
    }

    buildTypes {
        getByName("release") {
            // R8/ProGuard
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    // Ejemplo: FindBugs
    implementation("com.google.code.findbugs:jsr305:3.0.2")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    // Compose
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.material:material:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.6")

    // BouncyCastle (para ChaCha20-Poly1305)
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")

    // EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // ZXing (QR)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.1")

    // --- AÑADIR ESTA LÍNEA PARA BIOMETRÍA ---
    implementation("androidx.biometric:biometric:1.1.0")

    // Tests
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
