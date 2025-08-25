plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `groovy-gradle-plugin`
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

        create("witness") {
            id = "witness"
            implementationClass = "WitnessPlugin"
        }

        create("rename-apk") {
            id = "rename-apk"
            implementationClass = "RenameApkPlugin"
        }
    }
}