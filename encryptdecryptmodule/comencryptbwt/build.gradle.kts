// Archivo raíz de Gradle (nivel de proyecto)

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Android Gradle Plugin 8.5.0 (AGP 8.5)
        classpath("com.android.tools.build:gradle:8.5.0")

        // Plugin de Kotlin 1.9.10
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.10")
    }
}

// Tarea global 'clean' (opcional)
tasks.register("clean") {
    delete(rootProject.buildDir)
}
