// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0 moves the Compose compiler into the Kotlin repo — this plugin replaces the
    // old composeOptions { kotlinCompilerExtensionVersion } pinning entirely.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}

// Relocate all build outputs OUTSIDE the OneDrive-synced project tree.
// OneDrive grabs files under app/build mid-build and causes "Unable to delete
// directory ... zip-cache" / file-lock failures. Keeping build artifacts local
// (and out of sync) fixes that; source is versioned via git/GitHub instead.
// Applied only where that local root exists (the OneDrive dev machine) — CI and
// other checkouts use Gradle's default build/ dirs.
val localBuildRoot = File("C:/Users/bastr/.androidbuild/WhisperEverywhere")
if (localBuildRoot.isDirectory) {
    allprojects {
        layout.buildDirectory.set(File(localBuildRoot, project.name))
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
