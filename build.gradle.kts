// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("com.android.library") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.20" apply false
}

// Relocate all build outputs OUTSIDE the OneDrive-synced project tree.
// OneDrive grabs files under app/build mid-build and causes "Unable to delete
// directory ... zip-cache" / file-lock failures. Keeping build artifacts local
// (and out of sync) fixes that; source is versioned via git/GitHub instead.
allprojects {
    layout.buildDirectory.set(
        file("C:/Users/bastr/.androidbuild/WhisperEverywhere/${project.name}")
    )
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
