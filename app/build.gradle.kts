import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing secrets live OUTSIDE the repo and OneDrive:
//  - keystore:  C:\Users\bastr\.keystores\whispereverywhere-release.jks
//  - passwords: <project root>\keystore.properties  (gitignored)
// Never hardcode credentials here — this file is tracked in a repo with a public remote.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.whispereverywhere"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
            // Keep the ninja build tree OUT of OneDrive: the default .cxx staging dir lives in
            // the (OneDrive-synced) project folder, and OneDrive's placeholder/reparse handling
            // corrupts hard-linked build outputs ("Cannot snapshot ... not a regular file").
            buildStagingDirectory = file("C:/Users/bastr/.androidbuild/WhisperEverywhere/cxx-staging")
        }
    }

    defaultConfig {
        applicationId = "com.whispereverywhere"
        minSdk = 26
        targetSdk = 36
        versionCode = 78
        versionName = "3.7.0"  // VAD endpointing: Silero cuts at real pauses, per-tier commit cadence, eco/base retired

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Ship symbol tables with the AAB so native whisper/ggml crashes in Play vitals
            // arrive symbolicated instead of as raw addresses. Cheapest observability win.
            debugSymbolLevel = "SYMBOL_TABLE"
        }
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // Ensure the 16 KB page-size flag reaches every native target (incl. ggml/whisper),
                // not just whisper_jni — required for Play Store Android 15+ compliance.
                arguments += "-DANDROID_STL=c++_shared"
                // Compile ggml's fast quantized kernels (ARM dot-product + int8 matmul). These are
                // runtime-dispatched via getauxval, so ONE .so is fast on capable CPUs (2-4x for
                // quantized whisper) and falls back safely on older ones. Baseline stays armv8-a
                // (crash-safe across the minSdk-26 range); +fp16 is intentionally omitted (it is
                // NOT runtime-gated and would SIGILL on the oldest armv8.0 cores).
                arguments += "-DGGML_CPU_ARM_ARCH=armv8-a+dotprod+i8mm"
                // GPU = Qualcomm's ggml OpenCL backend (Adreno 750 officially supported; kernels
                // embedded via Python; Adreno-optimized matmuls ON — safe for .en models whose
                // vocab 51864 is %4==0; the multilingual model needs re-test re upstream #3708).
                // Headers: Khronos OpenCL-Headers; lib: libOpenCL.so pulled from the Fold 6.
                // Backends as dlopen-able MODULES (Track B): libggml no longer hard-links the
                // OpenCL backend, so ONE apk runs everywhere — the JNI scans the native-lib dir
                // at startup and loads every backend that CAN load (CPU always; OpenCL only on
                // devices whose vendor ships libOpenCL.so). Without this, System.loadLibrary
                // died on Tensor/Mali devices before CPU transcription could even exist.
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_OPENCL=ON"
                arguments += "-DOpenCL_INCLUDE_DIR=D:/gemma-inference/tools/opencl/include"
                arguments += "-DOpenCL_LIBRARY=D:/gemma-inference/tools/opencl/lib/libOpenCL.so"
                // CMake otherwise picks the Windows-Store python alias stub and fails.
                arguments += "-DPython3_EXECUTABLE=C:/Users/bastr/AppData/Local/Programs/Python/Python313/python.exe"
                // Vulkan CLOSED on Adreno (driver-compiler aborts + DeviceLost at Queue::submit,
                // proven on-device 2026-07-17). Revisit only for Mali/Xclipse experiments.
                arguments += "-DGGML_VULKAN=OFF"
                // 16 KB page-size alignment (Play requirement): the dlopen backend MODULES
                // (libggml-cpu/opencl) linked at 4 KB — cmake MODULE targets dodge the flag the
                // SHARED targets get. Force it on both linker classes (verified via
                // llvm-readelf LOAD p_align, 2026-07-18).
                arguments += "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
                arguments += "-DCMAKE_MODULE_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
                cppFlags += "-std=c++17"
            }
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
            // keystore.properties absent -> unsigned release (CI / fresh checkout); never fall
            // back to hardcoded credentials.
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // Dev machine only (props present): sign debug with the release key so debug/test
            // APKs install straight over the release build without uninstalling (which would
            // wipe the downloaded model). CI/fresh checkouts keep the default debug keystore.
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // NEVER bundle the vendor OpenCL loader we link against at build time — the app must
            // use the DEVICE's own /vendor copy (see uses-native-library in the manifest).
            // Bundling it breaks dlopen: our namespace can't resolve its private libc++.so dep.
            excludes += "**/libOpenCL.so"
            // sherpa-onnx AAR dead weight: the Kotlin API needs only libsherpa-onnx-jni.so
            // (+ libonnxruntime.so, its sole non-system DT_NEEDED — verified via llvm-readelf).
            // The C/CXX API libs and the parakeet model runtime are ~5 MB of unused payload.
            excludes += "**/libsherpa-onnx-c-api.so"
            excludes += "**/libsherpa-onnx-cxx-api.so"
            excludes += "**/libparakeet.so"
        }
    }

    testOptions {
        // Diagnostic logging uses android.util.Log, which is not available in plain JVM unit
        // tests; return default (no-op) values instead of throwing "Method not mocked".
        unitTests.isReturnDefaultValues = true
    }
}

// NativeVadSourceContractTest asserts over C++ SOURCE TEXT, but Gradle cannot see that: the .cpp
// files are inputs to the CMake tasks, never to the JVM test task, so a change confined to native
// sources leaves :app:testDebugUnitTest UP-TO-DATE and the contract goes unchecked. Verified: a
// one-line perturbation of the vendored fork produced "Task :app:testDebugUnitTest UP-TO-DATE /
// 27 actionable tasks: 27 up-to-date / BUILD SUCCESSFUL" without running a single test — which is
// precisely the shape of an upstream merge that re-promotes the demoted VAD logs. Declaring the two
// guarded files as explicit test inputs makes that change invalidate the task, so the guard fires.
tasks.withType<Test>().configureEach {
    inputs.files(
        "src/main/cpp/whisper_jni.cpp",
        "src/main/cpp/whisper.cpp/src/whisper.cpp",
    ).withPropertyName("nativeSourceContract").withPathSensitivity(PathSensitivity.RELATIVE)
}

// sherpa-onnx AAR (on-device TTS, Track F): no official Maven coordinates exist (verified
// 2026-07-18) and *.aar is gitignored, so the pinned upstream release asset is fetched on
// demand and sha256-verified — self-healing for CI and fresh clones alike.
val sherpaAar = file("libs/sherpa-onnx-1.13.4.aar")
val sherpaAarSha256 = "03f9c4df965f21c71269365a7951a7f23b5696fddd093fa318c80d65550ab780"
val fetchSherpaAar = tasks.register("fetchSherpaAar") {
    outputs.file(sherpaAar)
    doLast {
        if (!sherpaAar.exists()) {
            sherpaAar.parentFile.mkdirs()
            uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.4/sherpa-onnx-1.13.4.aar")
                .toURL().openStream().use { input ->
                    sherpaAar.outputStream().use { input.copyTo(it) }
                }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sherpaAar.readBytes())
            .joinToString("") { b -> "%02x".format(b) }
        check(digest == sherpaAarSha256) {
            "sherpa-onnx AAR sha256 mismatch ($digest) — delete app/libs and re-run"
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchSherpaAar) }

dependencies {
    // On-device TTS (Track F): sherpa-onnx runs Kokoro-82M on CPU (fetched above). arm64
    // native payload only reaches the APK because of the abiFilters above.
    implementation(files("libs/sherpa-onnx-1.13.4.aar"))

    // Core Android
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // JSON Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // tar.bz2 extraction for the TTS voice archive (Track F)
    implementation("org.apache.commons:commons-compress:1.27.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Accompanist for permissions
    implementation("com.google.accompanist:accompanist-permissions:0.36.0")

    // OkHttp is PINNED TO 4.12.0 — do not "upgrade" it to 5.x without also moving Kotlin.
    //
    // okhttp 5.4.0's Android artifact depends on kotlin-stdlib 2.2.21, and Gradle's conflict
    // resolution then forces the whole project to 2.2.21. That breaks this project's Kotlin
    // 2.0.21 compiler outright: `compileDebugKotlin` fails with "metadata is 2.2.0, expected
    // 2.0.0". Verified by `:app:dependencies`, which shows `kotlin-stdlib:2.0.21 -> 2.2.21`.
    // 4.12.0 leaves the stdlib at 2.0.21 and has everything needed here, including WebSocket
    // support for the streaming work.
    //
    // Also do NOT add okhttp-coroutines: it pulls kotlinx-coroutines 1.11.0, whose metadata has
    // the same problem. The Call.await() bridge in net/HttpTransport.kt is hand-rolled for that
    // reason.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
