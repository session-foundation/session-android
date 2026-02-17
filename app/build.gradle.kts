import com.android.build.api.dsl.VariantDimension
import com.android.build.api.variant.FilterConfiguration
import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.plugin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.dependency.analysis)
    alias(libs.plugins.google.services)

    id("generate-ip-country-data")
    id("local-snode-pool")
    id("rename-apk")
    id("witness")
}

val huaweiEnabled = project.properties["huawei"] != null
val hasIncludedLibSessionUtilProject: Boolean = System.getProperty("session.libsession_util.project.path", "").isNotBlank()

configurations.configureEach {
    exclude(module = "commons-logging")
}

val canonicalVersionCode = 439
val canonicalVersionName = "1.31.2"

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

val firebaseEnabledVariants = listOf("play", "fdroid")
val nonPlayVariants = listOf("fdroid", "website") + if (huaweiEnabled) listOf("huawei") else emptyList()
val nonDebugBuildTypes = listOf("release", "releaseWithDebugMenu", "qa", "automaticQa")

fun VariantDimension.devNetDefaultOn(defaultOn: Boolean) {
    val fqEnumClass = "org.session.libsession.utilities.Environment"
    buildConfigField(
        fqEnumClass,
        "DEFAULT_ENVIRONMENT",
        if (defaultOn) "$fqEnumClass.DEV_NET" else "$fqEnumClass.MAIN_NET"
    )
}

fun VariantDimension.enablePermissiveNetworkSecurityConfig(permissive: Boolean) {
    manifestPlaceholders["network_security_config"] = if (permissive) {
        "@xml/network_security_configuration_permissive"
    } else {
        "@xml/network_security_configuration"
    }
}

fun VariantDimension.setAlternativeAppName(alternative: String?) {
    if (alternative != null) {
        manifestPlaceholders["app_name"] = alternative
    } else {
        manifestPlaceholders["app_name"] = "@string/app_name"
    }
}

fun VariantDimension.setAuthorityPostfix(postfix: String) {
    manifestPlaceholders["authority_postfix"] = postfix
    buildConfigField("String", "AUTHORITY_POSTFIX", "\"$postfix\"")
}

kotlin {
    compilerOptions {
        jvmToolchain(21)
    }
}

val testJvmAgent = configurations.create("mockitoAgent")

android {
    namespace = "network.loki.messenger"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
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

        buildConfigField("org.thoughtcrime.securesms.pro.ProBackendConfig", "PRO_BACKEND_DEV", """
            new org.thoughtcrime.securesms.pro.ProBackendConfig(
                "https://pro-backend-dev.getsession.org",
                "fc947730f49eb01427a66e050733294d9e520e545c7a27125a780634e0860a27"
            )
        """.trimIndent())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        testOptions {
            execution = "ANDROIDX_TEST_ORCHESTRATOR"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                file("proguard-rules.pro")
            )
            devNetDefaultOn(false)
            enablePermissiveNetworkSecurityConfig(false)
            setAlternativeAppName(null)
            setAuthorityPostfix("")
        }

        create("releaseWithDebugMenu") {
            initWith(getByName("release"))

            matchingFallbacks += "release"
        }

        create("qa") {
            initWith(getByName("release"))

            matchingFallbacks += "release"

            signingConfig = signingConfigs.getByName("debug")

            devNetDefaultOn(false)
            enablePermissiveNetworkSecurityConfig(true)

            setAlternativeAppName("Session QA")
            setAuthorityPostfix("")
        }

        create("automaticQa") {
            initWith(getByName("qa"))

            devNetDefaultOn(true)
            setAlternativeAppName("Session AQA")
        }

        getByName("debug") {
            isDefault = true
            enableUnitTestCoverage = false
            signingConfig = signingConfigs.getByName("debug")

            applicationIdSuffix = ".${name}"
            enablePermissiveNetworkSecurityConfig(true)
            devNetDefaultOn(false)
            setAlternativeAppName("Session Debug")
            setAuthorityPostfix(".debug")
        }
    }

    testBuildType = "debug"

    sourceSets {
        val firebaseCommonDir = "src/firebaseCommon"
        firebaseEnabledVariants.forEach { variant ->
            maybeCreate(variant).kotlin.directories += "$firebaseCommonDir/kotlin"
        }

        val nonPlayCommonDir = "src/nonPlayCommon"
        nonPlayVariants.forEach { variant ->
            maybeCreate(variant).apply {
                kotlin.directories += "$nonPlayCommonDir/kotlin"
                resources.directories += "$nonPlayCommonDir/resources"
            }
        }

        val nonDebugDir = "src/nonDebug"
        nonDebugBuildTypes.forEach { buildType ->
            maybeCreate(buildType).apply {
                kotlin.directories += "$nonDebugDir/kotlin"
                resources.directories += "$nonDebugDir/resources"
            }
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
            buildConfigField("boolean", "PLAY_STORE_DISABLED", "false")
            buildConfigField("org.session.libsession.utilities.Device", "DEVICE", "org.session.libsession.utilities.Device.ANDROID")
            buildConfigField("String", "PUSH_KEY_SUFFIX", "\"\"")
            signingConfig = signingConfigs.getByName("play")
        }

        create("fdroid") {
            initWith(getByName("play"))
        }

        if (huaweiEnabled) {
            create("huawei") {
                dimension = "distribution"
                buildConfigField("boolean", "PLAY_STORE_DISABLED", "true")
                buildConfigField("org.session.libsession.utilities.Device", "DEVICE", "org.session.libsession.utilities.Device.HUAWEI")
                buildConfigField("String", "PUSH_KEY_SUFFIX", "\"_HUAWEI\"")
                signingConfig = signingConfigs.getByName("huawei")
            }
        }

        create("website") {
            initWith(getByName("play"))

            buildConfigField("boolean", "PLAY_STORE_DISABLED", "true")
        }

    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.jvmArgs("-javaagent:${testJvmAgent.asPath}")
        }
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
    implementation(libs.roundedimageview)
    implementation(libs.hilt.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    implementation(libs.flexbox)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.legacy.preference.v14)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.interpolator)

    // Add firebase dependencies to specific variants
    for (variant in firebaseEnabledVariants) {
        val configuration = configurations.maybeCreate("${variant}Implementation")
        configuration(libs.firebase.messaging) {
            exclude(group = "com.google.firebase", module = "firebase-core")
            exclude(group = "com.google.firebase", module = "firebase-analytics")
            exclude(group = "com.google.firebase", module = "firebase-measurement-connector")
        }
    }

    val playImplementation = configurations.maybeCreate("playImplementation")
    playImplementation(libs.google.play.review)
    playImplementation(libs.google.play.review.ktx)

    if (huaweiEnabled) {
        val huaweiImplementation = configurations.maybeCreate("huaweiImplementation")
        huaweiImplementation(libs.huawei.push)

        // These are compileOnly on the Huawei flavor so R8 can resolve optional HMS classes
        // referenced by HMS Push during minification.
        compileOnly(libs.huawei.hianalytics)
        compileOnly(libs.huawei.availableupdate)
    }

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common.ktx)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.media3.ui.compose.material3)

    implementation(libs.conscrypt.android)
    implementation(libs.android)
    implementation(libs.photoview)
    implementation(libs.glide)
    implementation(libs.glide.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.android.image.cropper)
    implementation(libs.subsampling.scale.image.view) {
        exclude(group = "com.android.support", module = "support-annotations")
    }
    implementation(libs.androidx.sqlite.ktx)
    implementation(libs.sqlcipher.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jackson.databind)
    implementation(libs.okhttp)
    implementation(libs.phrase)
    implementation(libs.copper.flow)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.opencsv)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.rxbinding)

    // If libsession_util project is included into the build, use that, otherwise use the published version
    findProject(":libsession-util-android")?.let(::implementation)
        ?: implementation(libs.libsession.util.android)

    implementation(libs.kryo)
    testImplementation(libs.junit)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.kotlin)
    androidTestImplementation(libs.mockito.core)
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
    testImplementation(libs.androidx.sqlite.framework)
    androidTestImplementation(libs.truth)
    testRuntimeOnly(libs.mockito.core)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlin.test)

    // Pull in appropriate JVM agents for unit test
    testJvmAgent(libs.mockito.core) { isTransitive = false }

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

    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.zxing.core)

    implementation(libs.androidx.biometric)
    implementation(libs.autolink)

    playImplementation(libs.android.billing)
    playImplementation(libs.android.billing.ktx)

    debugImplementation(libs.sqlite.web.viewer)
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

// Only enable google services tasks for firebase-enabled variants
androidComponents {
    finalizeDsl {
        tasks.named { it.contains("GoogleServices") }
            .configureEach {
                enabled = firebaseEnabledVariants.any { name.contains(it, true) }
            }
    }
}