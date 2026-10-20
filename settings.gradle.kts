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

rootProject.name = "HongGuoTV-Lite"

include(":core")
include(":data")
include(":uikit")
include(":feature-catalog")
include(":feature-detail")
include(":feature-player")
include(":app")
