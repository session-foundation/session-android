plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kotlin.plugin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    defaultConfig {
        compileSdk = libs.versions.androidCompileSdkVersion.get().toInt()
        minSdk = libs.versions.androidMinSdkVersion.get().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The following argument makes the Android Test Orchestrator run its
        // "pm clear" command after each test invocation. This command ensures
        // that the app's state is completely cleared between tests.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        testOptions {
            execution = "ANDROIDX_TEST_ORCHESTRATOR"
        }

        sourceSets {
            getByName("test").java.srcDirs("src/AndroidTest/java/org/session/libsession")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    namespace = "org.session.libsession"
}

dependencies {
    implementation(project(":libsignal"))
    implementation(project(":liblazysodium"))

    implementation(libs.hilt.android)
    ksp(libs.dagger.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    api(libs.libsession.util.android)


    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.protobuf.java)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.glide)
    implementation(libs.stream)
    implementation(libs.jackson.databind)
    implementation(libs.okhttp)
    implementation(libs.phrase)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kovenant)

    implementation(libs.kotlinx.datetime)

    testImplementation(libs.junit)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.core.testing)
    testImplementation(libs.kotlinx.coroutines.testing)
    testImplementation(libs.conscrypt.openjdk.uber)
    implementation(libs.eventbus)
}