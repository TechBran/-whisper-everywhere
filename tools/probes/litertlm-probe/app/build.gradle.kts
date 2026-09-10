import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

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
        jniLibs {
            useLegacyPackaging = true
            // sherpa-onnx AAR dead weight, excluded exactly as the main app excludes it (app/build.gradle.kts:167-172):
            // the Kotlin API needs libsherpa-onnx-jni.so + libonnxruntime.so only.
            excludes += "**/libsherpa-onnx-c-api.so"
            excludes += "**/libsherpa-onnx-cxx-api.so"
        }
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

// Default 2.1.1: the LAST LiteRT release whose NPU zip ships the MediaTek dispatch + compiler plugin, and the
// runtime every number in docs/measurements/2026-09-09-tab-apu-probe.md was taken with. `-PlitertVersion=2.2.0`
// builds the newer runtime (GPU/CPU only on this device: its libLiteRt.so cannot load the v2.1.1 MediaTek plugin).
val litertVersion: String = (project.findProperty("litertVersion") as String?) ?: "2.1.1"

// sherpa-onnx AAR for `mode=sherpa` (rung 3 of the streaming-local-tier plan). Default 1.13.7 = the artefact the
// main app ships since 4.3.4 (ORT 1.27.1 inside — the KleidiAI ConvolveSme fix, k2-fsa/sherpa-onnx#3845/#3791);
// `-PsherpaVersion=1.13.4` builds the comparison arm on the previously shipped AAR (ORT 1.27.0). *.aar is
// gitignored repo-wide, so the file is taken from the main checkout's app/libs when it is there (the main build
// fetched and verified it), else fetched from the GitHub release — and sha256-verified either way, every build.
val sherpaVersion: String = (project.findProperty("sherpaVersion") as String?) ?: "1.13.7"
val sherpaSha256 = mapOf(
    // computed 2026-09-10 on the upstream release assets (49,113,869 B and 48,847,529 B as published)
    "1.13.7" to "c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8",
    "1.13.4" to "03f9c4df965f21c71269365a7951a7f23b5696fddd093fa318c80d65550ab780",
)
val sherpaAar = file("libs/sherpa-onnx-${sherpaVersion}.aar")
val fetchSherpaAar = tasks.register("fetchSherpaAar") {
    outputs.file(sherpaAar)
    doLast {
        val expected = sherpaSha256[sherpaVersion] ?: error("no pinned sha256 for sherpa-onnx $sherpaVersion")
        if (!sherpaAar.exists()) {
            sherpaAar.parentFile.mkdirs()
            val mainCopy = rootProject.file("../../../app/libs/sherpa-onnx-${sherpaVersion}.aar")
            if (mainCopy.exists()) {
                mainCopy.copyTo(sherpaAar, overwrite = true)
            } else {
                uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/v${sherpaVersion}/sherpa-onnx-${sherpaVersion}.aar")
                    .toURL().openStream().use { input -> sherpaAar.outputStream().use { out -> input.copyTo(out) } }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(sherpaAar.readBytes())
            .joinToString("") { b: Byte -> "%02x".format(b) }
        check(digest == expected) { "sherpa-onnx-${sherpaVersion}.aar sha256 mismatch ($digest) — delete app/libs and re-run" }
        println("sherpa-onnx-${sherpaVersion}.aar ${sherpaAar.length()} B sha256 $digest OK")
    }
}
tasks.named("preBuild") { dependsOn(fetchSherpaAar) }

dependencies {
    // LiteRT (CompiledModel / Accelerator.{CPU,GPU,NPU}). 2.2.0 is the latest on Google Maven
    // (maven-metadata lastUpdated 2026-08-13); its AAR ships libLiteRt.so + libLiteRtClGlAccelerator.so
    // (the GPU accelerator; 2.1.1 names it libLiteRtOpenClAccelerator.so). The old 1.x `litert-gpu`
    // artefact (latest 1.4.2) is the TFLite Interpreter GPU delegate, not the CompiledModel accelerator,
    // and is deliberately NOT added. Google Maven's com.google.ai.edge.litert group carries NO vendor
    // runtime artefact (group-index.xml read 2026-09-10: litert, litert-api, litert-gpu(-api),
    // litert-metadata, litert-support(-api) only), so the MediaTek pair can only come from the release zip.
    // Measured 2026-09-09: the v2.1.1 libLiteRtCompilerPlugin_MediaTek.so does not load against 2.2.0's
    // libLiteRt.so (`dlopen failed: cannot locate symbol "LiteRtMediatekOptionsGet"` -- 2.2.0 exports none
    // of the 17 LiteRtMediatekOptions* symbols that 2.1.1 does), hence the 2.1.1 default above.
    implementation("com.google.ai.edge.litert:litert:${litertVersion}")
    // LiteRT-LM Android (Engine / EngineConfig / Backend.NPU(nativeLibraryDir)); 0.17.0 is the latest
    // (maven-metadata lastUpdated 2026-09-04).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // sherpa-onnx (OnlineRecognizer for mode=sherpa; see fetchSherpaAar above). arm64-v8a only, like the app.
    implementation(files("libs/sherpa-onnx-${sherpaVersion}.aar"))
}
