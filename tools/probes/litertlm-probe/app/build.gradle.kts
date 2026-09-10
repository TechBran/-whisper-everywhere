import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.whispereverywhere.probe"
    compileSdk = 36

    defaultConfig {
        // NEVER com.whispereverywhere — this sideloads BESIDE the Play copy and must never collide with it.
        applicationId = "com.whispereverywhere.probe"
        minSdk = 31          // LiteRT NPU support requires API 31+ (developers.google.com/edge/litert/next/npu)
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-probe"
        ndk { abiFilters += listOf("arm64-v8a") }   // NPU only supports arm64-v8a
    }

    buildTypes {
        debug {
            isDebuggable = true   // run-as needs a debuggable package; this is the only build type used
        }
        release {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Extract the .so files to applicationInfo.nativeLibraryDir. Both LiteRT (Environment.Option.
        // DispatchLibraryDir / CompilerPluginLibraryDir) and LiteRT-LM (Backend.NPU(nativeLibraryDir))
        // dlopen the vendor dispatch library BY PATH from that directory, which only works when the
        // libraries are really on disk there (not left compressed/aligned inside the APK).
        jniLibs { useLegacyPackaging = true }
    }

    sourceSets {
        getByName("main") {
            // fetch_mediatek_runtime.py drops libLiteRtDispatch_MediaTek.so + libLiteRtCompilerPlugin_MediaTek.so
            // (LiteRT v2.1.1's litert_npu_runtime_libraries_jit.zip, mediatek_runtime/) here. Gitignored.
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

val litertVersion: String = (project.findProperty("litertVersion") as String?) ?: "2.2.0"

dependencies {
    // LiteRT (CompiledModel / Accelerator.{CPU,GPU,NPU}); 2.2.0 is the latest on Google Maven
    // (maven-metadata lastUpdated 2026-08-13). Its AAR ships libLiteRt.so + libLiteRtClGlAccelerator.so
    // (the GPU accelerator) — the old 1.x `litert-gpu` artefact (latest 1.4.2) is the TFLite Interpreter
    // GPU delegate, not the CompiledModel accelerator, and is deliberately NOT added.
    // Overridable: `-PlitertVersion=2.1.1` pairs the runtime with the LAST LiteRT release whose NPU zip
    // shipped the MediaTek dispatch + compiler plugin (v2.1.1, 2026-01-27). Measured 2026-09-09: the
    // v2.1.1 libLiteRtCompilerPlugin_MediaTek.so does not load against 2.2.0's libLiteRt.so
    // (`dlopen failed: cannot locate symbol "LiteRtMediatekOptionsGet"` -- 2.2.0 exports none of the 17
    // LiteRtMediatekOptions* symbols that 2.1.1 does), so the NPU arm is built with 2.1.1.
    implementation("com.google.ai.edge.litert:litert:${litertVersion}")
    // LiteRT-LM Android (Engine / EngineConfig / Backend.NPU(nativeLibraryDir)); 0.17.0 is the latest
    // (maven-metadata lastUpdated 2026-09-04).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
