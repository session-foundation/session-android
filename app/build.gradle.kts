import com.android.build.api.variant.FilterConfiguration
import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.plugin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.dependency.analysis)
    alias(libs.plugins.google.services)

    id("generate-ip-country-data")
    id("rename-apk")
    id("witness")
}

val huaweiEnabled = project.properties["huawei"] != null

configurations.configureEach {
    exclude(module = "commons-logging")
}

val canonicalVersionCode = 407
val canonicalVersionName = "1.23.1"

val postFixSize = 10
val abiPostFix = mapOf(
    "armeabi-v7a" to 1,
    "arm64-v8a" to 2,
    "x86" to 3,
    "x86_64" to 4,
    "universal" to 5
)

val getGitHash = providers
    .exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
    }
    .standardOutput
    .asText
    .map { it.trim() }

android {
    namespace = "network.loki.messenger"
    useLibrary("org.apache.http.legacy")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += listOf(
            "LICENSE.txt", "LICENSE", "NOTICE", "asm-license.txt",
            "META-INF/LICENSE", "META-INF/NOTICE", "META-INF/proguard/androidx-annotations.pro"
        )
    }

    splits {
        abi {
            isEnable = !huaweiEnabled
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.kotlinComposeCompilerVersion.get()
    }

    defaultConfig {
        versionCode = canonicalVersionCode * postFixSize
        versionName = canonicalVersionName

        compileSdk = libs.versions.androidCompileSdkVersion.get().toInt()
        minSdk = libs.versions.androidMinSdkVersion.get().toInt()
        targetSdk = libs.versions.androidTargetSdkVersion.get().toInt()

        multiDexEnabled = true

        vectorDrawables.useSupportLibrary = true

        buildConfigField("long", "BUILD_TIMESTAMP", "${getLastCommitTimestamp()}L")
        buildConfigField("String", "GIT_HASH", "\"${getGitHash.get()}\"")
        buildConfigField("String", "CONTENT_PROXY_HOST", "\"contentproxy.signal.org\"")
        buildConfigField("int", "CONTENT_PROXY_PORT", "443")
        buildConfigField("String", "USER_AGENT", "\"OWA\"")
        buildConfigField("int", "CANONICAL_VERSION_CODE", "$canonicalVersionCode")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        testOptions {
            execution = "ANDROIDX_TEST_ORCHESTRATOR"
        }
    }

    sourceSets {
        val sharedTestDir = "src/sharedTest/java"
        getByName("test").java.srcDirs(sharedTestDir)
        getByName("androidTest").java.srcDirs(sharedTestDir)

        getByName("test").resources.srcDirs("$projectDir/src/main/assets")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }

        getByName("debug") {
            isDefault = true
            isMinifyEnabled = false
            enableUnitTestCoverage = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    signingConfigs {
        create("play") {
            if (project.hasProperty("SESSION_STORE_FILE")) {
                storeFile = file(project.property("SESSION_STORE_FILE")!!)
                storePassword = project.property("SESSION_STORE_PASSWORD") as? String
                keyAlias = project.property("SESSION_KEY_ALIAS") as? String
                keyPassword = project.property("SESSION_KEY_PASSWORD") as? String
            }
        }

        if (huaweiEnabled) {
            create("huawei") {
                if (project.hasProperty("SESSION_HUAWEI_STORE_FILE")) {
                    storeFile = file(project.property("SESSION_HUAWEI_STORE_FILE")!!)
                    storePassword = project.property("SESSION_HUAWEI_STORE_PASSWORD") as? String
                    keyAlias = project.property("SESSION_HUAWEI_KEY_ALIAS") as? String
                    keyPassword = project.property("SESSION_HUAWEI_KEY_PASSWORD") as? String
                }
            }
        }

        getByName("debug") {
            storeFile = file("${rootProject.projectDir}/etc/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            isDefault = true
            dimension = "distribution"
            ext["websiteUpdateUrl"] = "null"
            buildConfigField("boolean", "PLAY_STORE_DISABLED", "false")
            buildConfigField("org.session.libsession.utilities.Device", "DEVICE", "org.session.libsession.utilities.Device.ANDROID")
            buildConfigField("String", "NOPLAY_UPDATE_URL", ext["websiteUpdateUrl"] as String)
            buildConfigField("String", "PUSH_KEY_SUFFIX", "\"\"")
            signingConfig = signingConfigs.getByName("play")
        }

        if (huaweiEnabled) {
            create("huawei") {
                dimension = "distribution"
                ext["websiteUpdateUrl"] = "null"
                buildConfigField("boolean", "PLAY_STORE_DISABLED", "true")
                buildConfigField("org.session.libsession.utilities.Device", "DEVICE", "org.session.libsession.utilities.Device.HUAWEI")
                buildConfigField("String", "NOPLAY_UPDATE_URL", ext["websiteUpdateUrl"] as String)
                buildConfigField("String", "PUSH_KEY_SUFFIX", "\"_HUAWEI\"")
                signingConfig = signingConfigs.getByName("huawei")
            }
        }

        create("website") {
            dimension = "distribution"
            ext["websiteUpdateUrl"] = "https://github.com/session-foundation/session-android/releases"
            buildConfigField("boolean", "PLAY_STORE_DISABLED", "true")
            buildConfigField("org.session.libsession.utilities.Device", "DEVICE", "org.session.libsession.utilities.Device.ANDROID")
            buildConfigField("String", "NOPLAY_UPDATE_URL", "\"${ext["websiteUpdateUrl"]}\"")
            buildConfigField("String", "PUSH_KEY_SUFFIX", "\"\"")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        baseline = file("lint-baseline.xml")
    }

    tasks.register<JacocoReport>("testPlayDebugUnitTestCoverageReport") {
        dependsOn("testPlayDebugUnitTest")

        reports {
            xml.required.set(true)
        }

        val fileFilter = emptyList<String>()
        val mainSrc = "$projectDir/src/main/java"
        val buildDir = project.layout.buildDirectory.get().asFile
        val kotlinDebugTree = fileTree("${buildDir}/tmp/kotlin-classes/playDebug") {
            exclude(fileFilter)
        }

        classDirectories.setFrom(files(kotlinDebugTree))
        sourceDirectories.setFrom(files(mainSrc))
        executionData.setFrom(file("${buildDir}/outputs/unit_test_code_coverage/playDebugUnitTest/testPlayDebugUnitTest.exec"))
    }

    testNamespace = "network.loki.messenger.test"
}

dependencies {
    implementation(project(":content-descriptions"))

    ksp(libs.androidx.hilt.compiler)
    ksp(libs.dagger.hilt.compiler)
    ksp(libs.glide.ksp)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)

    implementation(libs.hilt.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    implementation(libs.flexbox)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.legacy.preference.v14)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.core.ktx)

    val playImplementation = configurations.maybeCreate("playImplementation")
    playImplementation(libs.firebase.messaging) {
        exclude(group = "com.google.firebase", module = "firebase-core")
        exclude(group = "com.google.firebase", module = "firebase-analytics")
        exclude(group = "com.google.firebase", module = "firebase-measurement-connector")
    }

    if (huaweiEnabled) {
        val huaweiImplementation = configurations.maybeCreate("huaweiImplementation")
        huaweiImplementation(libs.huawei.push)
    }

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.conscrypt.android)
    implementation(libs.aesgcmprovider)
    implementation(libs.android)
    implementation(libs.shortcutbadger)
    implementation(libs.photoview)
    implementation(libs.glide)
    implementation(libs.compose)
    implementation(libs.eventbus)
    implementation(libs.android.image.cropper)
    implementation(libs.subsampling.scale.image.view) {
        exclude(group = "com.android.support", module = "support-annotations")
    }
    implementation(libs.tooltips) {
        exclude(group = "com.android.support", module = "appcompat-v7")
    }
    implementation(libs.kinkerapps.android.smsmms) {
        exclude(group = "com.squareup.okhttp", module = "okhttp")
        exclude(group = "com.squareup.okhttp", module = "okhttp-urlconnection")
    }
    implementation(libs.stream)
    implementation(libs.androidx.sqlite.ktx)
    implementation(libs.sqlcipher.android)
    implementation(project(":libsignal"))
    implementation(project(":libsession"))
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":liblazysodium"))
    implementation(libs.protobuf.java)
    implementation(libs.jackson.databind)
    implementation(libs.okhttp)
    implementation(libs.phrase)
    implementation(libs.copper.flow)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kovenant)
    implementation(libs.kovenant.android)
    implementation(libs.opencsv)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.rxbinding)
    testImplementation(libs.junit)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.kotlin)
    androidTestImplementation(libs.mockito.android)
    androidTestImplementation(libs.mockito.kotlin)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.core.testing)
    testImplementation(libs.kotlinx.coroutines.testing)
    androidTestImplementation(libs.kotlinx.coroutines.testing)
    androidTestImplementation(libs.androidx.core)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.androidx.rules)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.truth)
    testImplementation(libs.truth)
    androidTestImplementation(libs.truth)
    testRuntimeOnly(libs.mockito.core)

    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.espresso.contrib)
    androidTestImplementation(libs.androidx.espresso.intents)
    androidTestImplementation(libs.androidx.espresso.accessibility)
    androidTestImplementation(libs.androidx.espresso.web)
    androidTestImplementation(libs.androidx.idling.concurrent)
    androidTestImplementation(libs.androidx.espresso.idling.resource)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestUtil(libs.androidx.orchestrator)

    testImplementation(libs.robolectric)
    testImplementation(libs.robolectric.shadows.multidex)
    testImplementation(libs.conscrypt.openjdk.uber)
    testImplementation(libs.turbine)

    implementation(platform(libs.androidx.compose.bom))
    testImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.material3)

    androidTestImplementation(libs.androidx.ui.test.junit4.android)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.accompanist.permissions)
    implementation(libs.accompanist.drawablepainter)

    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.zxing.core)

    implementation(libs.androidx.biometric)
}

fun getLastCommitTimestamp(): String {
    return ByteArrayOutputStream().use { os ->
        os.toString() + "000"
    }
}

fun autoResConfig(): List<String> {
    val files = mutableListOf<String>()
    val root = file("src/main/res")
    root.listFiles()?.forEach { files.add(it.name) }
    return listOf("en") + files.mapNotNull { it.takeIf { f -> f.startsWith("values-") }?.substringAfter("values-") }
        .sorted()
}

// Assign version code postfix to APKs based on ABI
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abiName = output.filters.firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier ?: "universal"
            val versionCodeAdditions = checkNotNull(abiPostFix[abiName]) { "$abiName does not exist" }
            output.versionCode.set(canonicalVersionCode * postFixSize + versionCodeAdditions)
        }
    }
}

// Disable google services for non-google variants
androidComponents {
    finalizeDsl {
        tasks.named { it.contains("GoogleServices") }
            .configureEach { enabled = name.contains("play", true) }
    }
}