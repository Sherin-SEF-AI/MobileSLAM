pluginManagement {
    includeBuild("build-logic")
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MapPilot"

include(":app")

// core
include(":core:common")
include(":core:model")
include(":core:database")
include(":core:time-sync")

// sensors
include(":sensors:camera")
include(":sensors:imu")
include(":sensors:gnss")

// recording
include(":recording:mcap")
include(":recording:video")

// slam
include(":slam:core")
include(":slam:arcore")
include(":slam:fusion")

// perception
include(":perception:core")
include(":perception:detection")
include(":perception:depth")

// assets / geo
include(":assets:extraction")
include(":geo:trajectory")
include(":geo:mapping")

// search / export / cloud
include(":search")
include(":export")
include(":cloud:client")

// viz / analytics
include(":viz:map")
include(":viz:render3d")
include(":analytics")
