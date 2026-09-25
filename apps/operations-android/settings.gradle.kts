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

rootProject.name = "nexa-operations-android"
include(
    ":app",
    ":core:auth",
    ":core:network",
    ":core:designsystem",
    ":feature:access",
    ":feature:warehouse"
)
