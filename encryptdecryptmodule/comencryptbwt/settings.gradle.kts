dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Nombre de tu proyecto, y módulos incluidos
rootProject.name = "com.encrypt.bwt"
include(":app")
