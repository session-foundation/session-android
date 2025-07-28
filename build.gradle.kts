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
            classpath("com.huawei.agconnect:agcp:1.9.3.301")
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
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
        maven {
            url = uri("https://raw.github.com/signalapp/maven/master/photoview/releases/")
            content {
                includeGroupByRegex("com\\.github\\.chrisbanes.*")
            }
        }
        maven {
            url = uri("https://raw.github.com/signalapp/maven/master/shortcutbadger/releases/")
            content {
                includeGroupByRegex("me\\.leolin.*")
            }
        }
        maven {
            url = uri("https://raw.github.com/signalapp/maven/master/sqlcipher/release/")
            content {
                includeGroupByRegex("org\\.signal.*")
            }
        }
        maven { url = uri("https://jitpack.io") }
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