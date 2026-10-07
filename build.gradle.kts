import org.gradle.platform.Architecture
import org.gradle.platform.BuildPlatformFactory
import org.gradle.platform.OperatingSystem

buildscript {
    repositories {
        google()
        mavenCentral()
        if (project.hasProperty("huawei")) {
            maven {
                url = uri("https://developer.huawei.com/repo/")
                content {
                    includeGroup("com.huawei.agconnect")
                }
            }
        }
    }

    dependencies {
//        classpath(files("libs/gradle-witness.jar"))
//        classpath("com.squareup:javapoet:1.13.0")
        if (project.hasProperty("huawei")) {
            classpath("com.huawei.agconnect:agcp:1.9.5.302")
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
    alias(libs.plugins.kotlin.plugin.parcelize) apply false
    alias(libs.plugins.kotlin.plugin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.dependency.analysis) apply false
}

allprojects {
    repositories {
        maven {
            url = uri("https://oxen.rocks/session-foundation/libsession-util-android/maven")
            content {
                includeGroup("org.sessionfoundation")
            }
        }

        google()
        mavenCentral()
        if (project.hasProperty("huawei")) {
            maven {
                url = uri("https://developer.huawei.com/repo/")
                content {
                    includeGroup("com.huawei.android.hms")
                    includeGroup("com.huawei.agconnect")
                    includeGroup("com.huawei.hmf")
                    includeGroup("com.huawei.hms")
                }
            }
        }
    }
}

// Generates gradle/gradle-daemon-jvm.properties. Any vendor's JDK 21 can run the daemon, so CI's
// setup-java JDK and Android Studio's JBR are used as they are; a machine without one downloads
// Adoptium's Temurin release directly rather than through the api.foojay.io index. Adoptium has no
// FreeBSD or other Unix builds, so those get the Linux ones, as Gradle's own foojay lookup does.
tasks.updateDaemonJvm {
    val temurin = "21.0.12.1+1"
    val release = "https://github.com/adoptium/temurin21-binaries/releases/download/" +
        "jdk-${temurin.replace("+", "%2B")}/OpenJDK21U-jdk"
    val file = temurin.replace("+", "_")
    val linuxAarch64 = uri("${release}_aarch64_linux_hotspot_$file.tar.gz")
    val linuxX64 = uri("${release}_x64_linux_hotspot_$file.tar.gz")

    languageVersion = JavaLanguageVersion.of(21)
    toolchainDownloadUrls = mapOf(
        BuildPlatformFactory.of(Architecture.AARCH64, OperatingSystem.LINUX) to linuxAarch64,
        BuildPlatformFactory.of(Architecture.X86_64, OperatingSystem.LINUX) to linuxX64,
        BuildPlatformFactory.of(Architecture.AARCH64, OperatingSystem.FREE_BSD) to linuxAarch64,
        BuildPlatformFactory.of(Architecture.X86_64, OperatingSystem.FREE_BSD) to linuxX64,
        BuildPlatformFactory.of(Architecture.AARCH64, OperatingSystem.UNIX) to linuxAarch64,
        BuildPlatformFactory.of(Architecture.X86_64, OperatingSystem.UNIX) to linuxX64,
        BuildPlatformFactory.of(Architecture.AARCH64, OperatingSystem.MAC_OS) to
            uri("${release}_aarch64_mac_hotspot_$file.tar.gz"),
        BuildPlatformFactory.of(Architecture.X86_64, OperatingSystem.MAC_OS) to
            uri("${release}_x64_mac_hotspot_$file.tar.gz"),
        BuildPlatformFactory.of(Architecture.AARCH64, OperatingSystem.WINDOWS) to
            uri("${release}_aarch64_windows_hotspot_$file.zip"),
        BuildPlatformFactory.of(Architecture.X86_64, OperatingSystem.WINDOWS) to
            uri("${release}_x64_windows_hotspot_$file.zip"),
    )
    toolchainPlatforms = toolchainDownloadUrls.get().keys
}
