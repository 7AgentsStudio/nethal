pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NetHAL"

include(":core")
include(":core:model")
include(":core:protocol")
include(":core:catalog")
include(":core:discovery")
include(":core:fingerprint")
include(":core:capability")
include(":core:auth")
include(":core:consent")
include(":app")
