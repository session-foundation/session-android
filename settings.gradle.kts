pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "session-android"

includeBuild("build-logic")

include(":app")
include(":liblazysodium")
include(":content-descriptions") // ONLY AccessibilityID strings (non-translated) used to identify UI elements in automated testing