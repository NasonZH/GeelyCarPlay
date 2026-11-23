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

rootProject.name = "geely-carplay"
include(":app")

include(":shared", ":jmdns")
project(":jmdns").projectDir = file("vendor/jmdns")
