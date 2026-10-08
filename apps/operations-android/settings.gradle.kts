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
    ":core:local",
    ":core:network",
    ":core:designsystem",
    ":core:device",
    ":feature:access",
    ":feature:warehouse",
    ":feature:dispatch",
    ":feature:delivery",
    ":feature:commercial",
    ":feature:access:contract",
    ":feature:warehouse:contract",
    ":feature:dispatch:contract",
    ":feature:delivery:contract",
    ":feature:commercial:contract",
    ":data:operations"
)
