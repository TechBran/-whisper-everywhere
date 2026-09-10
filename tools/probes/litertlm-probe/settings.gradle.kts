// litertlm-probe — a throwaway, separately-named probe app for the Galaxy Tab S10+ (MT6989) APU gates
// (research doc 2026-09-09 §1.4 E3 / E4-lite / E5). It is its own Gradle build on purpose: the main
// Whisper Everywhere app is never touched, never built and never installed by anything in this tree.
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

rootProject.name = "litertlm-probe"
include(":app")
