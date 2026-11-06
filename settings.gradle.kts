pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // GeckoView is only published here. JCenter and third-party mirrors are not used.
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}

rootProject.name = "GeckoDownloader"
include(":app")
