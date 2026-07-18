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
    compileSdk = 35
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
        targetSdk = 35
        versionCode = 50
        versionName = "2.9.1"  // Track D: multilingual→CPU gate (silent GPU corruption), engine prewarm

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
        }
    }

    testOptions {
        // Diagnostic logging uses android.util.Log, which is not available in plain JVM unit
        // tests; return default (no-op) values instead of throwing "Method not mocked".
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
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

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Security - Encrypted SharedPreferences for API key storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Accompanist for permissions
    implementation("com.google.accompanist:accompanist-permissions:0.36.0")

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
