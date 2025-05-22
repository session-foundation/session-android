pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "session-android"

includeBuild("build-logic")

// If libsession_util_project_path is set, include it as a build dependency
val libSessionUtilProjectPath: String = System.getProperty("session.libsession_util.project.path", "")
if (libSessionUtilProjectPath.isNotBlank()) {
    includeBuild(libSessionUtilProjectPath)
}

include(":app")
include(":content-descriptions") // ONLY AccessibilityID strings (non-translated) used to identify UI elements in automated testing