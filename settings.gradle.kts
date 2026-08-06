pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "session-android"

includeBuild("build-logic")

// Dev-only: build the glue (libsession-util-android) from source, in one Gradle run with the app,
// instead of pulling the prebuilt AAR — so coordinated glue+app changes can be live-tested locally.
// Production/CI builds leave this unset and use the published AAR. The glue checkout path may come from
// either (system property wins, so it can override the file for a one-off):
//   * -Dsession.libsession_util.project.path=<repo>            on the command line, or
//   * session.libsession_util.project.path=<repo>              in local.properties (gitignored, set-once)
val libSessionUtilProjectPath: String = System.getProperty("session.libsession_util.project.path", "")
    .ifBlank {
        val localProps = file("local.properties")
        if (localProps.exists()) {
            java.util.Properties()
                .apply { localProps.inputStream().use(::load) }
                .getProperty("session.libsession_util.project.path", "")
        } else ""
    }
if (libSessionUtilProjectPath.isNotBlank()) {
    include(":libsession-util-android")
    project(":libsession-util-android").projectDir = file(libSessionUtilProjectPath).resolve("library")
}

include(":app")
include(":content-descriptions") // ONLY AccessibilityID strings (non-translated) used to identify UI elements in automated testing