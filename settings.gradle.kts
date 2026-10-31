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

rootProject.name = "Lumen"

include(
    ":app",
    ":core:designsystem",
    ":core:apps",
    ":core:settings",
    ":core:system",
    ":feature:home",
    ":feature:settings",
)
