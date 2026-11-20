pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") { content { includeGroupByRegex("com\\.github\\.MuntashirAkon.*") } }
    }
}

rootProject.name = "VoiceBox"
include(":app", ":bridge-api", ":core", ":model:gptsovits", ":model:ming")
