rootProject.name = "NaviLink"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":app")
// OSM -> Garmin .img builder shared with other projects (bbnav...): no NaviLink code in it.
include(":osmimg")
