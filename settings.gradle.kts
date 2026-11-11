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
        // NewPipeExtractor (YouTube stream extraction) is published on JitPack
        maven("https://jitpack.io")
    }
}

rootProject.name = "Sonora"
include(":app")
