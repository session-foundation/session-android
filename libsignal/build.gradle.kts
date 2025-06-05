plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    defaultConfig {
        compileSdk = libs.versions.androidCompileSdkVersion.get().toInt()
        minSdk = libs.versions.androidMinSdkVersion.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        buildConfig = true
    }

    namespace = "org.session.libsignal"
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.protobuf.java)
    implementation(libs.jackson.databind)
    implementation(libs.curve25519.java)
    implementation(libs.okhttp)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kovenant)
    testImplementation(libs.junit)
    testImplementation(libs.assertj.core)
    testImplementation(libs.conscrypt.openjdk.uber)
}