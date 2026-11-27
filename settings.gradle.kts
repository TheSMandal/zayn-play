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
        // JitPack: NewPipeExtractor (com.github.TeamNewPipe) is not
        // on Maven Central. Added in v0.2.10 for YoutubeProvider.
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "Zayn Play"
include(":app")
