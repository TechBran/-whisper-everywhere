plugins {
    // AGP pinned to the main repo's version (already in the Gradle cache). Kotlin 2.4.x is REQUIRED:
    // litertlm-android 0.17.0's classes.jar carries Kotlin metadata 2.4 (javap -v: mv = 2,4), which a
    // 2.2.x compiler refuses ("incompatible version of Kotlin"); litert-api 2.2.0 is mv 2.3.
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}

// Build outputs live OUTSIDE OneDrive (same reason as the main repo: OneDrive locks files mid-build).
val localBuildRoot = File("C:/Users/bastr/.androidbuild/litertlm-probe")
if (localBuildRoot.isDirectory) {
    allprojects {
        layout.buildDirectory.set(File(localBuildRoot, project.name))
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
