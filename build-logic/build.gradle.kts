plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(libs.agpApi)
    gradleApi()
}

gradlePlugin {
    plugins {
        create("generate-ip-country-data") {
            id = "generate-ip-country-data"
            implementationClass = "GenerateIPCountryDataPlugin"
        }

        create("witness-plugin") {
            id = "witness-plugin"
            implementationClass = "org.whispersystems.witness.WitnessPlugin"
        }

        create("rename-apk") {
            id = "rename-apk"
            implementationClass = "RenameApkPlugin"
        }
    }
}