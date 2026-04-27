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

rootProject.name = "smart-traffic-android"
include(":app")
include(":designsystem")
include(":coreengine")

project(":designsystem").projectDir = file("../design_system")
project(":coreengine").projectDir = file("../core_engine")

