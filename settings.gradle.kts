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

rootProject.name = "PingClash"
include(":diagnostics", ":app")
include(":terminal-emulator", ":terminal-view")


include(":backdrop")
project(":backdrop").projectDir = file("third-party/backdrop")
