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

rootProject.name = "RogerDash"
include(":app") // RogerDash: navigation, calls and messages on the Suzuki V-Strom 250 SX TFT dashboard; ride and fuel log
