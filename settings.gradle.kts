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

rootProject.name = "WhisperEverywhere"
include(":app")
// The two on-demand NPU asset-pack modules (4.2 F4). Their payload is a BUILD artifact —
// tools/build_asset_packs.py build assembles the SoC #group_ variants from the measured vendor
// zips — so each committed module tree carries only its build file, the EMPTY default variant
// (model/.gitkeep) and the .gitignore that keeps the payload dirs out.
include(":npu_turbo", ":npu_small")
