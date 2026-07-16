# On-Device Whisper - Plan 1: Core Engine (Implementation)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the cloud STT backend with an on-device whisper.cpp engine so Whisper Everywhere transcribes fully offline, leaving the existing recorder -> VAD -> inject pipeline unchanged.

**Architecture:** A native whisper.cpp JNI layer (`WhisperNative`) behind an injectable `WhisperBackend`; a `LocalWhisperEngine` implementing the `TranscriptionEngine` contract buffers 16 kHz PCM16 and transcribes each VAD-committed segment as a fast batch with bounded retry (`RetryPolicy`); a `WhisperModelManager` resolves/downloads GGML models. `FloatingBubbleService` swaps the cloud client for `LocalWhisperEngine` with no other pipeline changes.

**Tech Stack:** Kotlin, Jetpack Compose, Android NDK r27 + CMake, whisper.cpp/ggml (C++17), JUnit4 + kotlinx-coroutines-test, Android DownloadManager.

## Global Constraints

- Package root `com.whispereverywhere`; single `:app` module; minSdk 26, targetSdk 35.
- Audio is **16 kHz mono PCM16** end to end (whisper's native rate; no resampling).
- Native ABI **arm64-v8a**; NDK **r27+**; `.so` built **16 KB page-aligned** (`-Wl,-z,max-page-size=16384`) for Play Store Android 15+.
- TDD: every task is failing test -> run -> implement -> run -> commit. JVM unit tests under `app/src/test/...`; JNI-dependent checks are instrumented under `app/src/androidTest/...`.
- Build/test (Windows): `.\gradlew.bat testDebugUnitTest`, `.\gradlew.bat assembleDebug`.
- Shared interfaces are fixed by `docs/superpowers/specs/2026-07-15-on-device-whisper-design.md` - do not rename across tasks.
- No secrets; no network at transcription time; models live in `filesDir/models`.
- **Indefinite length:** sessions run unbounded (book/movie/hours) at bounded memory - audio buffer cleared per committed segment (VAD max-segment cap), whisper context loaded once and reused, finalized text streamed to a session file with only a capped in-memory preview, and a failed segment is skipped (never stalls the session). See Task 7.

## Prerequisites

- **Android NDK r27+ and CMake** must be installed — Tasks 1, 5, 6, 7 all run `assembleDebug`, which builds the native `.so`s:
  ```bash
  sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"
  ```
  AGP resolves the NDK from `ndkVersion` in `app/build.gradle.kts` (set in Task 1); no `ndk.dir` needed with a standard SDK. Verify before starting:
  ```bash
  sdkmanager --list_installed | findstr /i "ndk;27 cmake;3.22"
  ```
  **Without the NDK, every `assembleDebug` gate (Tasks 1, 5, 6, 7) fails at the native build — not just Task 1.**
- A connected arm64 device or emulator for the instrumented tests (Tasks 1, 5) and manual checks (Tasks 6, 7).

---

### Task 1: Native whisper.cpp layer (NDK/CMake/JNI + WhisperNative)

**Files:**
- Create (submodule): `app/src/main/cpp/whisper.cpp` (git submodule → `https://github.com/ggerganov/whisper.cpp`)
- Create: `app/src/main/cpp/CMakeLists.txt`
- Create: `app/src/main/cpp/whisper_jni.cpp`
- Create: `app/src/main/java/com/whispereverywhere/whisper/WhisperNative.kt`
- Modify: `app/build.gradle.kts` (add `externalNativeBuild`, `ndk { abiFilters }`, `ndkVersion`, native linker flags in `defaultConfig`)
- Modify: `app/proguard-rules.pro` (JNI keep rules for `WhisperNative` native methods)
- Create (test asset): `app/src/androidTest/assets/jfk.wav` (bundled 16 kHz mono PCM sample from `whisper.cpp/samples/jfk.wav`)
- Test: `app/src/androidTest/java/com/whispereverywhere/whisper/WhisperNativeSmokeTest.kt` (instrumented — JNI cannot run in a JVM unit test)

**Interfaces:**
- Consumes: nothing from other tasks (this is the base layer). Requires the NDK (r27.x) installed and CMake available to the Android Gradle plugin.
- Produces (relied on by Task 4 `WhisperNativeBackend` / Task 2 audio path):
  ```kotlin
  object WhisperNative {
      external fun init(modelPath: String): Long                                             // whisper_context*, 0 = failure
      external fun transcribe(ctxPtr: Long, samples: FloatArray, lang: String?, translate: Boolean): String
      external fun free(ctxPtr: Long)
  }   // static init loads "whisper_jni" via System.loadLibrary
  ```

> Note: This task's real verification is an **instrumented** test (`connectedDebugAndroidTest`), because `System.loadLibrary`/JNI cannot execute in a JVM `testDebugUnitTest`. The `testDebugUnitTest` gate stays green because there is no JVM unit test here; the failing→passing loop runs on a connected device/emulator. NDK **must** be installed (`sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"`).

- [ ] **Step 1: Vendor whisper.cpp as a git submodule under cpp/**
  Add whisper.cpp (which contains ggml) as a submodule so CMake can reference it, and check in the bundled sample used by the smoke test.
  ```bash
  cd "C:/Users/bastr/OneDrive/Desktop/whisper Everywhere"
  git submodule add https://github.com/ggerganov/whisper.cpp app/src/main/cpp/whisper.cpp
  git -C app/src/main/cpp/whisper.cpp checkout v1.7.4
  git add .gitmodules app/src/main/cpp/whisper.cpp
  # bundle the canonical 11s JFK sample for the instrumented smoke test
  mkdir -p app/src/androidTest/assets
  cp app/src/main/cpp/whisper.cpp/samples/jfk.wav app/src/androidTest/assets/jfk.wav
  git add app/src/androidTest/assets/jfk.wav
  git commit -m "Task1: vendor whisper.cpp submodule + bundle jfk.wav test asset"
  ```
  Expected: `.gitmodules` records `path = app/src/main/cpp/whisper.cpp`, and `app/src/main/cpp/whisper.cpp/CMakeLists.txt` + `app/src/main/cpp/whisper.cpp/include/whisper.h` (or `whisper.cpp/whisper.h` depending on tag) exist on disk.

- [ ] **Step 2: Write the failing instrumented smoke test (REAL, COMPLETE)**
  This is the driving test. It copies the bundled `jfk.wav` and a sideloaded tiny model out of instrumentation args / assets into app storage, calls `WhisperNative.init/transcribe/free`, and asserts non-empty text containing a known word. Create `app/src/androidTest/java/com/whispereverywhere/whisper/WhisperNativeSmokeTest.kt`:
  ```kotlin
  package com.whispereverywhere.whisper

  import androidx.test.ext.junit.runners.AndroidJUnit4
  import androidx.test.platform.app.InstrumentationRegistry
  import org.junit.Assert.assertNotEquals
  import org.junit.Assert.assertTrue
  import org.junit.Assume.assumeTrue
  import org.junit.Test
  import org.junit.runner.RunWith
  import java.io.File
  import java.nio.ByteBuffer
  import java.nio.ByteOrder

  @RunWith(AndroidJUnit4::class)
  class WhisperNativeSmokeTest {

      /**
       * Reads a 16-bit PCM mono WAV (any standard 44-byte header) into float32 [-1,1].
       * The bundled jfk.wav is 16 kHz mono, matching whisper's native rate.
       */
      private fun wavToFloat(bytes: ByteArray): FloatArray {
          // Locate the "data" chunk instead of assuming a fixed 44-byte header.
          var i = 12 // skip "RIFF"<size>"WAVE"
          var dataOffset = 44
          var dataLen = bytes.size - 44
          while (i + 8 <= bytes.size) {
              val id = String(bytes, i, 4, Charsets.US_ASCII)
              val chunkSize = ByteBuffer.wrap(bytes, i + 4, 4)
                  .order(ByteOrder.LITTLE_ENDIAN).int
              if (id == "data") {
                  dataOffset = i + 8
                  dataLen = chunkSize
                  break
              }
              i += 8 + chunkSize + (chunkSize and 1)
          }
          val sampleCount = dataLen / 2
          val out = FloatArray(sampleCount)
          val bb = ByteBuffer.wrap(bytes, dataOffset, dataLen).order(ByteOrder.LITTLE_ENDIAN)
          for (s in 0 until sampleCount) {
              out[s] = bb.short / 32768.0f
          }
          return out
      }

      @Test
      fun transcribes_bundled_jfk_to_nonempty_text() {
          val inst = InstrumentationRegistry.getInstrumentation()
          val ctx = inst.targetContext

          // Sideloaded tiny model path is passed as an instrumentation arg:
          //   -e modelPath /data/local/tmp/ggml-tiny.en-q5_1.bin
          // (see Step 6 for the adb push + run command). Skip cleanly if absent so
          // the suite doesn't hard-fail on machines without the sideloaded model.
          val args = InstrumentationRegistry.getArguments()
          val modelPath = args.getString("modelPath")
          assumeTrue("No sideloaded model (pass -e modelPath ...); skipping", modelPath != null)
          val modelFile = File(modelPath!!)
          assumeTrue("Model file missing at $modelPath", modelFile.exists())

          // Copy bundled jfk.wav (androidTest asset) to a real file, decode to float32.
          val wavBytes = inst.context.assets.open("jfk.wav").use { it.readBytes() }
          val samples = wavToFloat(wavBytes)
          assertTrue("decoded samples should be non-empty", samples.isNotEmpty())

          val ctxPtr = WhisperNative.init(modelFile.absolutePath)
          assertNotEquals("init() returned 0 (model failed to load)", 0L, ctxPtr)
          try {
              val text = WhisperNative.transcribe(ctxPtr, samples, "en", false)
              assertTrue("transcription should be non-empty", text.trim().isNotEmpty())
              // jfk.wav says "...ask not what your country can do for you...".
              assertTrue(
                  "expected recognizable JFK content, got: '$text'",
                  text.lowercase().contains("country") || text.lowercase().contains("ask")
              )
          } finally {
              WhisperNative.free(ctxPtr)
          }
      }
  }
  ```

- [ ] **Step 3: Run the test → expected FAIL (compilation/link failure)**
  The `WhisperNative` class, the JNI lib, and the native build do not exist yet, so the androidTest module fails to compile/assemble.
  ```
  .\gradlew.bat :app:compileDebugAndroidTestKotlin
  ```
  Expected failure: `Unresolved reference: WhisperNative` (and, once that's stubbed, `assembleDebugAndroidTest` fails because CMake/`whisper_jni` is not wired in / the model isn't sideloaded → the `@Test` throws `UnsatisfiedLinkError`). Either state is a valid RED for this step.

- [ ] **Step 4: Add the `WhisperNative` Kotlin object (REAL, COMPLETE)**
  Create `app/src/main/java/com/whispereverywhere/whisper/WhisperNative.kt` — exact signatures from the shared contract; the static initializer loads the JNI lib:
  ```kotlin
  package com.whispereverywhere.whisper

  /**
   * JNI bridge to the native whisper.cpp engine (libwhisper_jni.so).
   *
   * All three functions map 1:1 to whisper_jni.cpp:
   *   - init()       -> whisper_init_from_file_with_params(); returns whisper_context* as Long (0 = failure)
   *   - transcribe() -> whisper_full() with WHISPER_SAMPLING_GREEDY; returns concatenated segment text
   *   - free()       -> whisper_free()
   *
   * The returned Long is an opaque native pointer handle owned by the caller
   * (LocalWhisperEngine caches it). Never dereference it in Kotlin.
   */
  object WhisperNative {
      init {
          System.loadLibrary("whisper_jni")
      }

      /** Loads a ggml model file into a native whisper_context. Returns 0L on failure. */
      external fun init(modelPath: String): Long

      /**
       * Runs whisper_full on float32 PCM (mono, 16 kHz, [-1,1]).
       * @param ctxPtr handle from [init]
       * @param lang   ISO code (e.g. "en"), or null/"auto" for auto-detect
       * @param translate true to translate to English; false for transcribe-in-language
       */
      external fun transcribe(ctxPtr: Long, samples: FloatArray, lang: String?, translate: Boolean): String

      /** Frees the native whisper_context. Safe to call once per non-zero handle. */
      external fun free(ctxPtr: Long)
  }
  ```

- [ ] **Step 5: Add the JNI wrapper + CMake, then wire native build into Gradle (REAL, COMPLETE)**

  Create `app/src/main/cpp/whisper_jni.cpp` — the three JNI entry points. `whisper_full` uses `WHISPER_SAMPLING_GREEDY`, `n_threads = available cores`, honors `lang`/`translate`, and disables progress printing:
  ```cpp
  #include <jni.h>
  #include <string>
  #include <thread>
  #include <vector>
  #include <android/log.h>
  #include "whisper.h"

  #define LOG_TAG "whisper_jni"
  #define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
  #define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

  extern "C" JNIEXPORT jlong JNICALL
  Java_com_whispereverywhere_whisper_WhisperNative_init(
          JNIEnv *env, jobject /* this */, jstring modelPath) {
      const char *path = env->GetStringUTFChars(modelPath, nullptr);
      if (path == nullptr) {
          return 0;
      }
      whisper_context_params cparams = whisper_context_default_params();
      // CPU path for v1 (no GPU/OpenCL); mmap the model from filesDir.
      cparams.use_gpu = false;
      whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
      env->ReleaseStringUTFChars(modelPath, path);
      if (ctx == nullptr) {
          LOGE("whisper_init_from_file_with_params failed");
          return 0;
      }
      return reinterpret_cast<jlong>(ctx);
  }

  extern "C" JNIEXPORT jstring JNICALL
  Java_com_whispereverywhere_whisper_WhisperNative_transcribe(
          JNIEnv *env, jobject /* this */,
          jlong ctxPtr, jfloatArray samples, jstring lang, jboolean translate) {
      auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
      if (ctx == nullptr) {
          return env->NewStringUTF("");
      }

      const jsize n = env->GetArrayLength(samples);
      std::vector<float> pcm(static_cast<size_t>(n));
      if (n > 0) {
          env->GetFloatArrayRegion(samples, 0, n, pcm.data());
      }

      whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
      params.print_progress   = false;
      params.print_realtime   = false;
      params.print_timestamps = false;
      params.print_special    = false;
      params.translate        = (translate == JNI_TRUE);
      params.single_segment   = false;
      params.no_context       = true;

      int cores = static_cast<int>(std::thread::hardware_concurrency());
      if (cores <= 0) {
          cores = 4;
      }
      params.n_threads = cores;

      // Language handling: null / "auto" / "" -> auto-detect; otherwise force the code.
      std::string langStr;
      const char *langC = nullptr;
      if (lang != nullptr) {
          const char *raw = env->GetStringUTFChars(lang, nullptr);
          if (raw != nullptr) {
              langStr = raw;
              env->ReleaseStringUTFChars(lang, raw);
          }
      }
      if (langStr.empty() || langStr == "auto") {
          params.language     = "auto";
          params.detect_language = true;
      } else {
          langC = langStr.c_str();
          params.language     = langC;
          params.detect_language = false;
      }

      if (whisper_full(ctx, params, pcm.data(), static_cast<int>(pcm.size())) != 0) {
          LOGE("whisper_full failed");
          return env->NewStringUTF("");
      }

      std::string result;
      const int nSeg = whisper_full_n_segments(ctx);
      for (int i = 0; i < nSeg; ++i) {
          const char *seg = whisper_full_get_segment_text(ctx, i);
          if (seg != nullptr) {
              result += seg;
          }
      }
      return env->NewStringUTF(result.c_str());
  }

  extern "C" JNIEXPORT void JNICALL
  Java_com_whispereverywhere_whisper_WhisperNative_free(
          JNIEnv *env, jobject /* this */, jlong ctxPtr) {
      auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
      if (ctx != nullptr) {
          whisper_free(ctx);
      }
  }
  ```

  Create `app/src/main/cpp/CMakeLists.txt` — pulls in the vendored whisper.cpp (which builds ggml) and links a `whisper_jni` shared lib. The 16 KB page-size flag is applied to the JNI target's linker (Gradle also passes it project-wide in Step 6):
  ```cmake
  cmake_minimum_required(VERSION 3.22.1)
  project(whisper_jni LANGUAGES C CXX)

  set(CMAKE_CXX_STANDARD 17)
  set(CMAKE_CXX_STANDARD_REQUIRED ON)

  # Release-grade native code: -O3 + NEON is on by default for arm64-v8a.
  set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} -O3")
  set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")

  # 16 KB page alignment for Android 15+ / Play Store, applied to EVERY shared lib built here
  # (libggml*.so, libwhisper.so, libwhisper_jni.so) - not just the JNI target.
  set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")

  # Keep whisper.cpp lean for a mobile CPU build.
  set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
  set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
  set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

  # Vendored whisper.cpp submodule (builds ggml as a subtarget, exposes the `whisper` target).
  add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp)

  add_library(whisper_jni SHARED whisper_jni.cpp)

  target_link_libraries(whisper_jni
      whisper
      android
      log)

  # 16 KB page alignment for Android 15+ / Play Store (NDK r27+).
  target_link_options(whisper_jni PRIVATE "-Wl,-z,max-page-size=16384")
  ```

  Modify `app/build.gradle.kts`. In `defaultConfig` (after the `vectorDrawables { ... }` block, before its closing brace) add the `ndk` + `externalNativeBuild` cmake args:
  ```kotlin
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
                cppFlags += "-std=c++17"
            }
        }
  ```
  And in the `android { ... }` block (immediately after `compileSdk = 35`) pin the NDK and register the CMake project:
  ```kotlin
      compileSdk = 35
      ndkVersion = "27.0.12077973"

      externalNativeBuild {
          cmake {
              path = file("src/main/cpp/CMakeLists.txt")
              version = "3.22.1"
          }
      }
  ```
  The 16 KB page-size flag is set globally inside `CMakeLists.txt` (via `CMAKE_SHARED_LINKER_FLAGS`, above), so it applies to **all** native `.so` outputs (ggml, whisper, whisper_jni). Do **not** also pass `-DCMAKE_SHARED_LINKER_FLAGS` from Gradle — a `-D` cache value would override the CMake string and drop `common-page-size`.
  Modify `app/proguard-rules.pro` — add JNI keep rules so R8 (release) doesn't rename the native methods or the `WhisperNative` class (JNI resolves `Java_com_whispereverywhere_whisper_WhisperNative_*` by name):
  ```proguard
  # --- Native whisper.cpp JNI bridge (Task 1) ---
  -keepclasseswithmembernames class * { native <methods>; }
  -keep class com.whispereverywhere.whisper.WhisperNative { *; }
  ```

- [ ] **Step 6: Build native + run the instrumented smoke test → expected PASS**
  Verify the native lib compiles and links (this is also the `assembleDebug` gate for the native layer), then sideload a tiny model and run the instrumented test on a connected arm64 device/emulator. The bundled `jfk.wav` ships in the test APK; the model is pushed to `/data/local/tmp` and its path passed via `-e modelPath`.
  ```bash
  cd "C:/Users/bastr/OneDrive/Desktop/whisper Everywhere"
  # 1) native compile/link gate (arm64-v8a, 16 KB aligned)
  .\gradlew.bat :app:assembleDebug

  # 2) sideload a tiny model for the smoke test
  curl -L -o ggml-tiny.en-q5_1.bin \
    https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_1.bin
  adb push ggml-tiny.en-q5_1.bin /data/local/tmp/ggml-tiny.en-q5_1.bin

  # 3) run ONLY the smoke test, passing the sideloaded model path
  .\gradlew.bat :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.modelPath=/data/local/tmp/ggml-tiny.en-q5_1.bin \
    -Pandroid.testInstrumentationRunnerArguments.class=com.whispereverywhere.whisper.WhisperNativeSmokeTest
  ```
  Expected: `assembleDebug` produces `libwhisper_jni.so` (and `libwhisper.so`/`libggml*.so`) under `app/build/intermediates/.../arm64-v8a/`; the instrumented run reports `transcribes_bundled_jfk_to_nonempty_text PASSED`, and the transcript contains "country"/"ask". (On a machine with no device/model, the test is `assumeTrue`-skipped, not failed.) Verify 16 KB alignment on **every** produced `.so` (Play rejects the release upload if any one is 4 KB-aligned):
  ```bash
  READELF="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"
  LIBDIR="app/build/intermediates/merged_native_libs/debug/out/lib/arm64-v8a"
  for so in "$LIBDIR"/*.so; do
    echo "$so"
    "$READELF" -l "$so" | grep -i "LOAD" | head -1
  done
  # Every LOAD segment must show Align 0x4000 (16384), NOT 0x1000 -
  # covers libggml*.so, libwhisper.so, AND libwhisper_jni.so.
  ```

- [ ] **Step 7: Commit**
  ```bash
  cd "C:/Users/bastr/OneDrive/Desktop/whisper Everywhere"
  git add app/src/main/cpp/CMakeLists.txt \
          app/src/main/cpp/whisper_jni.cpp \
          app/src/main/java/com/whispereverywhere/whisper/WhisperNative.kt \
          app/src/androidTest/java/com/whispereverywhere/whisper/WhisperNativeSmokeTest.kt \
          app/build.gradle.kts \
          app/proguard-rules.pro
  git commit -m "Task1: native whisper.cpp JNI layer (CMake/NDK + WhisperNative) with 16KB-aligned arm64 build and jfk.wav instrumented smoke test"
  ```
  Expected: clean commit; `.\gradlew.bat testDebugUnitTest` stays green (no JVM unit tests added — JNI is validated by the instrumented `connectedDebugAndroidTest` above).

---

### Task 2: 16 kHz Capture + PCM16→float Conversion
**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/util/AudioMath.kt` (add `pcm16ToFloat`; keep existing `amplitude(buffer, length)`)
- Modify: `app/src/main/java/com/whispereverywhere/util/StreamingAudioRecorder.kt` (`companion object` `const val SAMPLE_RATE` 24000 → 16000; update KDoc "24 kHz" → "16 kHz")
- Test: `app/src/test/java/com/whispereverywhere/AudioMathTest.kt` (existing file — add `pcm16ToFloat` cases alongside existing `amplitude` tests)

**Interfaces:**
- Consumes: nothing from other tasks (leaf utility).
- Produces:
  - `AudioMath.pcm16ToFloat(pcm: ByteArray): FloatArray` — little-endian PCM16 → float32 in `[-1, 1]` (`sample / 32768f`, clamped). Odd trailing byte ignored. Consumed by `LocalWhisperEngine.commit()` (Task 4).
  - `StreamingAudioRecorder.SAMPLE_RATE == 16000` — whisper native rate.

**Steps:**

- [ ] **Step 1: Write the failing tests.** Append these methods inside the existing `AudioMathTest` class in `app/src/test/java/com/whispereverywhere/AudioMathTest.kt` (keep the existing three `amplitude` tests untouched; the imports `assertEquals`, `assertTrue`, `Test` are already present). Add one new import line `import org.junit.Assert.assertArrayEquals` just below the existing `import org.junit.Assert.assertTrue` line.

  ```kotlin
      @Test
      fun pcm16ToFloat_zero_is_zero() {
          val floats = AudioMath.pcm16ToFloat(byteArrayOf(0x00, 0x00))
          assertArrayEquals(floatArrayOf(0f), floats, 0f)
      }

      @Test
      fun pcm16ToFloat_positive_fullscale() {
          // +32767 (0x7FFF) little-endian -> 32767/32768 = 0.99997
          val floats = AudioMath.pcm16ToFloat(byteArrayOf(0xFF.toByte(), 0x7F))
          assertEquals(1, floats.size)
          assertEquals(0.99996948f, floats[0], 1e-6f)
      }

      @Test
      fun pcm16ToFloat_negative_fullscale_is_minus_one() {
          // -32768 (0x8000) little-endian -> -32768/32768 = -1.0 exactly
          val floats = AudioMath.pcm16ToFloat(byteArrayOf(0x00, 0x80.toByte()))
          assertArrayEquals(floatArrayOf(-1f), floats, 0f)
      }

      @Test
      fun pcm16ToFloat_negative_sample() {
          // -1 (0xFFFF) little-endian -> -1/32768 = -0.000030517578
          val floats = AudioMath.pcm16ToFloat(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
          assertEquals(1, floats.size)
          assertEquals(-3.0517578e-5f, floats[0], 1e-9f)
      }

      @Test
      fun pcm16ToFloat_multiple_known_samples() {
          // Samples: 0 (0x0000), +16384 (0x4000), -16384 (0xC000)
          val bytes = byteArrayOf(
              0x00, 0x00,          // 0
              0x00, 0x40,          // +16384 -> 0.5
              0x00, 0xC0.toByte(), // -16384 -> -0.5
          )
          val floats = AudioMath.pcm16ToFloat(bytes)
          assertArrayEquals(floatArrayOf(0f, 0.5f, -0.5f), floats, 0f)
      }

      @Test
      fun pcm16ToFloat_odd_length_drops_trailing_byte() {
          // 3 bytes -> one full sample (+16384 = 0.5) + one dangling byte ignored
          val floats = AudioMath.pcm16ToFloat(byteArrayOf(0x00, 0x40, 0x11))
          assertArrayEquals(floatArrayOf(0.5f), floats, 0f)
      }

      @Test
      fun pcm16ToFloat_empty_is_empty() {
          assertEquals(0, AudioMath.pcm16ToFloat(ByteArray(0)).size)
      }

      @Test
      fun pcm16ToFloat_single_byte_is_empty() {
          // odd-length guard: a lone byte is not a full sample
          assertEquals(0, AudioMath.pcm16ToFloat(byteArrayOf(0x42)).size)
      }
  ```

- [ ] **Step 2: Run → expect FAIL (compile error).** `pcm16ToFloat` does not exist yet.
  ```
  .\gradlew.bat testDebugUnitTest --tests "com.whispereverywhere.AudioMathTest"
  ```
  Expected: build fails with `unresolved reference: pcm16ToFloat` (Kotlin compile error), tests do not run.

- [ ] **Step 3: Implement `pcm16ToFloat`.** Add the function to `AudioMath` in `app/src/main/java/com/whispereverywhere/util/AudioMath.kt`, keeping the existing `amplitude` function unchanged. Full file:

  ```kotlin
  package com.whispereverywhere.util

  import kotlin.math.min
  import kotlin.math.sqrt

  /** Pure helpers for PCM16 audio. No Android dependencies. */
  object AudioMath {

      /**
       * Root-mean-square amplitude of the first [length] bytes of [buffer],
       * interpreted as 16-bit little-endian mono samples, scaled to 0..32767.
       */
      fun amplitude(buffer: ByteArray, length: Int): Int {
          val end = min(length, buffer.size)
          if (end < 2) return 0
          var sumSquares = 0.0
          var count = 0
          var i = 0
          while (i + 1 < end) {
              val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
              sumSquares += sample.toDouble() * sample.toDouble()
              count++
              i += 2
          }
          if (count == 0) return 0
          val rms = sqrt(sumSquares / count)
          return rms.toInt().coerceIn(0, 32767)
      }

      /**
       * Converts little-endian 16-bit PCM [pcm] to float32 samples in [-1, 1]
       * (each sample / 32768f, clamped). A trailing odd byte (incomplete sample)
       * is ignored. This is the exact format whisper.cpp's `whisper_full()` expects.
       */
      fun pcm16ToFloat(pcm: ByteArray): FloatArray {
          val sampleCount = pcm.size / 2
          val out = FloatArray(sampleCount)
          var i = 0
          while (i < sampleCount) {
              val lo = pcm[i * 2].toInt() and 0xFF
              val hi = pcm[i * 2 + 1].toInt() // sign-extended for the high byte
              val sample = (hi shl 8) or lo   // signed 16-bit, little-endian
              out[i] = (sample / 32768f).coerceIn(-1f, 1f)
              i++
          }
          return out
      }
  }
  ```

- [ ] **Step 4: Run → expect PASS.**
  ```
  .\gradlew.bat testDebugUnitTest --tests "com.whispereverywhere.AudioMathTest"
  ```
  Expected: `BUILD SUCCESSFUL`, all 11 tests pass (3 existing `amplitude` + 8 new `pcm16ToFloat`). This confirms the existing `AudioMathTest` still passes.

- [ ] **Step 5: Commit.**
  ```
  git add "app/src/main/java/com/whispereverywhere/util/AudioMath.kt" "app/src/test/java/com/whispereverywhere/AudioMathTest.kt"
  git commit -m "Add AudioMath.pcm16ToFloat little-endian PCM16 to float32 conversion"
  ```

- [ ] **Step 6: Change the capture rate to 16 kHz.** In `app/src/main/java/com/whispereverywhere/util/StreamingAudioRecorder.kt`, change the `SAMPLE_RATE` constant and the KDoc that says "24 kHz". Apply these two exact edits:

  Edit A — the constant:
  ```kotlin
      companion object {
          const val SAMPLE_RATE = 16000
          private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
          private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
      }
  ```
  (was `const val SAMPLE_RATE = 24000`.)

  Edit B — the class KDoc first line:
  ```kotlin
  /**
   * Streams 16 kHz mono PCM16 from the mic. For each buffer read it invokes
   * [onChunk] (off the main thread) and updates [amplitude] for the waveform.
   * No file is written — audio goes straight to the transcription engine.
   */
  ```
  (was "Streams 24 kHz mono PCM16 …" / "straight to the realtime client".)

- [ ] **Step 7: Verify compile + existing tests still green after the rate change.**
  ```
  .\gradlew.bat testDebugUnitTest
  ```
  Expected: `BUILD SUCCESSFUL`. `AudioMathTest` (11 tests) and any existing `SpeechSegmenter` tests pass — segmenter thresholds are RMS/ms-based and sample-rate-independent, so the 16 kHz change does not affect them.

- [ ] **Step 8: Commit.**
  ```
  git add "app/src/main/java/com/whispereverywhere/util/StreamingAudioRecorder.kt"
  git commit -m "Capture mic at 16 kHz (whisper native rate) instead of 24 kHz"
  ```

**Contract note:** the shared contract lists `amplitude(pcm: ByteArray)`, but the real existing signature is `amplitude(buffer: ByteArray, length: Int): Int` (used by `StreamingAudioRecorder` with a read count). I kept the real signature unchanged as the task requires; only `pcm16ToFloat(pcm: ByteArray): FloatArray` is added.

---

### Task 3: RetryPolicy (pure, deterministic)

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/util/RetryPolicy.kt`
- Modify: none
- Test: `app/src/test/java/com/whispereverywhere/util/RetryPolicyTest.kt`
- Modify (build): `app/build.gradle.kts` (add `testImplementation` for `kotlinx-coroutines-test`)

**Interfaces:**
- Consumes: nothing from other tasks (pure helper; only `kotlin.Math`, `kotlinx.coroutines.delay`).
- Produces (Task 4 `LocalWhisperEngine` relies on these EXACT signatures):
  - `class RetryPolicy(val maxAttempts: Int = 3, val baseDelayMs: Long = 200, val maxDelayMs: Long = 3000, val rng: () -> Double = { Math.random() })`
  - `fun delayForAttempt(attempt: Int): Long`
  - `suspend fun <T> retry(shouldRetry: (Throwable) -> Boolean = { true }, block: suspend (attempt: Int) -> T): T`

---

- [ ] **Step 1: Add coroutines-test dependency to `app/build.gradle.kts`.**
  Add to the `dependencies { }` block (version aligned with the coroutines BOM/version already used by the app; `1.7.3` matches the standard AGP/Kotlin baseline for minSdk 26). If a `kotlinx-coroutines-core` line already exists, keep its version and match it here.
  ```kotlin
      testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
  ```

- [ ] **Step 2: Write the failing test (REAL, COMPLETE).**
  Create `app/src/test/java/com/whispereverywhere/util/RetryPolicyTest.kt`:
  ```kotlin
  package com.whispereverywhere.util

  import kotlinx.coroutines.ExperimentalCoroutinesApi
  import kotlinx.coroutines.test.runTest
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertTrue
  import org.junit.Assert.fail
  import org.junit.Test
  import java.io.IOException

  @OptIn(ExperimentalCoroutinesApi::class)
  class RetryPolicyTest {

      // rng fixed to 0.0 -> jitter is always 0, so delays are deterministic.
      private fun noJitterPolicy(
          maxAttempts: Int = 3,
          baseDelayMs: Long = 200,
          maxDelayMs: Long = 3000,
      ) = RetryPolicy(
          maxAttempts = maxAttempts,
          baseDelayMs = baseDelayMs,
          maxDelayMs = maxDelayMs,
          rng = { 0.0 },
      )

      @Test
      fun succeedsOnFirstTry() = runTest {
          val policy = noJitterPolicy()
          var calls = 0
          val result = policy.retry { attempt ->
              calls++
              assertEquals(1, attempt)
              "ok"
          }
          assertEquals("ok", result)
          assertEquals(1, calls)
      }

      @Test
      fun retriesThenSucceeds() = runTest {
          val policy = noJitterPolicy()
          var calls = 0
          val seenAttempts = mutableListOf<Int>()
          val result = policy.retry { attempt ->
              seenAttempts += attempt
              calls++
              if (calls < 3) throw IOException("transient $calls")
              "done"
          }
          assertEquals("done", result)
          assertEquals(3, calls)
          assertEquals(listOf(1, 2, 3), seenAttempts)
      }

      @Test
      fun exhaustsAndRethrowsLastError() = runTest {
          val policy = noJitterPolicy(maxAttempts = 3)
          var calls = 0
          try {
              policy.retry<String> { attempt ->
                  calls++
                  throw IllegalStateException("boom $attempt")
              }
              fail("expected exception to be rethrown")
          } catch (e: IllegalStateException) {
              assertEquals("boom 3", e.message)
          }
          assertEquals(3, calls)
      }

      @Test
      fun shouldRetryFalseRethrowsImmediately() = runTest {
          val policy = noJitterPolicy(maxAttempts = 5)
          var calls = 0
          try {
              policy.retry<String>(shouldRetry = { false }) { _ ->
                  calls++
                  throw IOException("no retry")
              }
              fail("expected exception to be rethrown")
          } catch (e: IOException) {
              assertEquals("no retry", e.message)
          }
          assertEquals(1, calls)
      }

      @Test
      fun delayForAttemptIsMonotonicAndCapped() {
          val policy = noJitterPolicy(baseDelayMs = 200, maxDelayMs = 3000)
          // base * 2^(attempt-1), jitter=0:
          // attempt 1 -> 200, 2 -> 400, 3 -> 800, 4 -> 1600, 5 -> 3000 (capped), 6 -> 3000 (capped)
          assertEquals(200L, policy.delayForAttempt(1))
          assertEquals(400L, policy.delayForAttempt(2))
          assertEquals(800L, policy.delayForAttempt(3))
          assertEquals(1600L, policy.delayForAttempt(4))
          assertEquals(3000L, policy.delayForAttempt(5))
          assertEquals(3000L, policy.delayForAttempt(6))

          // Monotonic non-decreasing across a range and never above the cap.
          var prev = -1L
          for (attempt in 1..12) {
              val d = policy.delayForAttempt(attempt)
              assertTrue("delay must be <= maxDelayMs", d <= 3000L)
              assertTrue("delay must be non-decreasing", d >= prev)
              prev = d
          }
      }

      @Test
      fun jitterStaysWithinBaseAndUnderCap() {
          // rng at its max (just under 1.0) -> jitter approaches baseDelayMs but never reaches it.
          val policy = RetryPolicy(
              maxAttempts = 3,
              baseDelayMs = 200,
              maxDelayMs = 3000,
              rng = { 0.999 },
          )
          // attempt 1: 200 + floor(0.999*200)=199 -> 399, still < cap
          assertEquals(399L, policy.delayForAttempt(1))
          // capped attempt stays at cap even with jitter
          assertEquals(3000L, policy.delayForAttempt(9))
      }
  }
  ```

- [ ] **Step 3: Run the test -> expected FAIL.**
  ```
  .\gradlew.bat testDebugUnitTest --tests "com.whispereverywhere.util.RetryPolicyTest"
  ```
  Expected failure: compilation error — `Unresolved reference: RetryPolicy` (the class does not exist yet). Build fails before any test executes.

- [ ] **Step 4: Implement `RetryPolicy` (REAL, COMPLETE).**
  Create `app/src/main/java/com/whispereverywhere/util/RetryPolicy.kt`:
  ```kotlin
  package com.whispereverywhere.util

  import kotlinx.coroutines.delay

  /**
   * Pure, deterministic bounded-backoff retry helper.
   *
   * Applied at every fallible step (STT transcribe, model load, text generation) so the user only
   * sees an error after retries are exhausted. Determinism for tests comes from the injectable [rng].
   *
   * @param maxAttempts total number of tries including the first (>= 1).
   * @param baseDelayMs base backoff in ms; also the exclusive upper bound of the jitter window.
   * @param maxDelayMs hard cap applied to the computed delay.
   * @param rng returns a value in [0.0, 1.0); scaled by [baseDelayMs] to produce jitter.
   */
  class RetryPolicy(
      val maxAttempts: Int = 3,
      val baseDelayMs: Long = 200,
      val maxDelayMs: Long = 3000,
      val rng: () -> Double = { Math.random() },
  ) {
      /**
       * Delay before the given 1-based [attempt]'s *next* retry:
       * min(baseDelayMs * 2^(attempt-1) + jitter, maxDelayMs), where jitter is in [0, baseDelayMs).
       *
       * The exponent is clamped so very large attempt numbers cannot overflow the shift; the result
       * is capped at [maxDelayMs] regardless.
       */
      fun delayForAttempt(attempt: Int): Long {
          val safeAttempt = if (attempt < 1) 1 else attempt
          // Cap the shift to avoid Long overflow; anything this large is already past the cap.
          val shift = (safeAttempt - 1).coerceAtMost(62)
          val exponential = if (baseDelayMs <= 0L) 0L else baseDelayMs shl shift
          // jitter in [0, baseDelayMs): rng() is in [0.0, 1.0), floored -> 0..baseDelayMs-1.
          val jitter = (rng() * baseDelayMs).toLong()
          val raw = if (exponential >= Long.MAX_VALUE - jitter) Long.MAX_VALUE else exponential + jitter
          return if (raw > maxDelayMs) maxDelayMs else raw
      }

      /**
       * Runs [block] with a 1-based attempt index, from 1 up to [maxAttempts]. On a thrown
       * [Throwable], if [shouldRetry] returns true and attempts remain, [delay]s for
       * [delayForAttempt] then retries; otherwise rethrows.
       */
      suspend fun <T> retry(
          shouldRetry: (Throwable) -> Boolean = { true },
          block: suspend (attempt: Int) -> T,
      ): T {
          var attempt = 1
          while (true) {
              try {
                  return block(attempt)
              } catch (t: Throwable) {
                  val hasMore = attempt < maxAttempts
                  if (!hasMore || !shouldRetry(t)) throw t
                  delay(delayForAttempt(attempt))
                  attempt++
              }
          }
      }
  }
  ```

- [ ] **Step 5: Run the test -> expected PASS.**
  ```
  .\gradlew.bat testDebugUnitTest --tests "com.whispereverywhere.util.RetryPolicyTest"
  ```
  Expected: `BUILD SUCCESSFUL`, 6 tests passed. `runTest` auto-advances virtual time so the `delay(...)` in the retry path completes instantly (no wall-clock waiting).

- [ ] **Step 6: Commit.**
  ```
  git add app/src/main/java/com/whispereverywhere/util/RetryPolicy.kt app/src/test/java/com/whispereverywhere/util/RetryPolicyTest.kt app/build.gradle.kts
  git commit -m "Add RetryPolicy pure deterministic backoff helper with tests"
  ```

---

### Task 4: TranscriptionEngine + LocalWhisperEngine

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt`
- Create: `app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt`
- Test: `app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEngineTest.kt`

**Interfaces:**
- Consumes:
  - `WhisperNative.init(modelPath: String): Long`, `WhisperNative.transcribe(ctxPtr: Long, samples: FloatArray, lang: String?, translate: Boolean): String`, `WhisperNative.free(ctxPtr: Long)` (Task 1)
  - `AudioMath.pcm16ToFloat(pcm: ByteArray): FloatArray` (Task 2)
  - `RetryPolicy(maxAttempts: Int = 3, ...)` with `suspend fun <T> retry(shouldRetry: (Throwable) -> Boolean = { true }, block: suspend (attempt: Int) -> T): T` (Task 3)
  - (nothing from Task 5) — Task 4 **defines** `interface ModelPathProvider { fun installedModelPath(): String? }` and depends only on it, so it compiles independently of Task 5. Task 5's `WhisperModelManager` implements `ModelPathProvider`.
- Produces:
  - `interface TranscriptionEngine { fun connect(language: String?, listener: Listener); fun sendAudio(pcm: ByteArray); fun commit(); fun close(); interface Listener { fun onOpen(); fun onDelta(text: String); fun onCompleted(text: String); fun onError(message: String); fun onClosed() } }`
  - `interface WhisperBackend { fun load(modelPath: String): Long; fun transcribe(ctx: Long, samples: FloatArray, lang: String?): String; fun release(ctx: Long) }`
  - `object WhisperNativeBackend : WhisperBackend`
  - `interface ModelPathProvider { fun installedModelPath(): String? }` (defined in `TranscriptionEngine.kt`; implemented by Task 5's `WhisperModelManager`)
  - `class LocalWhisperEngine(private val modelPathProvider: ModelPathProvider, private val retry: RetryPolicy = RetryPolicy(maxAttempts = 3), private val backend: WhisperBackend = WhisperNativeBackend, executor: ExecutorService = Executors.newSingleThreadExecutor()) : TranscriptionEngine` — plus `fun releaseContext()`

**Steps:**

- [ ] **Step 1: Write the failing test (no-model → onError).**

Create `app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEngineTest.kt`. This file also defines the shared test fakes (`FakeWhisperBackend`, `SameThreadExecutorService`, `FakeModelManager`, `RecordingListener`) reused by every step below, so write the whole file now — later steps add `@Test` methods to it.

```kotlin
package com.whispereverywhere.transcription

import com.whispereverywhere.util.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/** Runs every submitted task immediately on the calling thread so tests are synchronous. */
class SameThreadExecutorService : AbstractExecutorService() {
    @Volatile private var shutdown = false
    override fun execute(command: Runnable) { command.run() }
    override fun shutdown() { shutdown = true }
    override fun shutdownNow(): MutableList<Runnable> { shutdown = true; return mutableListOf() }
    override fun isShutdown(): Boolean = shutdown
    override fun isTerminated(): Boolean = shutdown
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
}

/** Scriptable backend: fails the first [failTimes] transcribe calls, then returns [text]. */
class FakeWhisperBackend(
    private val text: String = "hello world",
    private var failTimes: Int = 0,
    private val loadReturns: Long = 42L,
) : WhisperBackend {
    val loadCalls = mutableListOf<String>()
    val transcribeCalls = mutableListOf<Triple<Long, FloatArray, String?>>()
    var releaseCalls = 0
    override fun load(modelPath: String): Long { loadCalls.add(modelPath); return loadReturns }
    override fun transcribe(ctx: Long, samples: FloatArray, lang: String?): String {
        transcribeCalls.add(Triple(ctx, samples, lang))
        if (failTimes > 0) { failTimes--; throw RuntimeException("transient transcribe failure") }
        return text
    }
    override fun release(ctx: Long) { releaseCalls++ }
}

/** Records every Listener callback for assertions. */
class RecordingListener : TranscriptionEngine.Listener {
    var opened = false
    var closed = false
    val deltas = mutableListOf<String>()
    val completed = mutableListOf<String>()
    val errors = mutableListOf<String>()
    override fun onOpen() { opened = true }
    override fun onDelta(text: String) { deltas.add(text) }
    override fun onCompleted(text: String) { completed.add(text) }
    override fun onError(message: String) { errors.add(message) }
    override fun onClosed() { closed = true }
}

/** Trivial ModelPathProvider fake: returns [path] from installedModelPath(). */
class FakeModelPathProvider(private val path: String?) : ModelPathProvider {
    override fun installedModelPath(): String? = path
}

class LocalWhisperEngineTest {

    // Zero-delay retry policy so retries don't slow the suite.
    private fun fastRetry() = RetryPolicy(maxAttempts = 3, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 })

    // 4 bytes PCM16 -> 2 samples; deterministic non-blank audio.
    private val pcm = byteArrayOf(0x10, 0x00, 0x20, 0x00)

    @Test
    fun connect_withNoInstalledModel_reportsError() {
        val backend = FakeWhisperBackend()
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider(null),
            retry = fastRetry(),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        val listener = RecordingListener()

        engine.connect(language = "en", listener = listener)

        assertEquals(listOf("No speech model installed"), listener.errors)
        assertTrue(listener.completed.isEmpty())
        assertTrue(backend.loadCalls.isEmpty())
    }
}
```

Note: Task 4 defines and depends only on `ModelPathProvider` (declared in `TranscriptionEngine.kt`), so it compiles and its tests pass **independently of Task 5**. Task 5's `WhisperModelManager` implements `ModelPathProvider`, so `LocalWhisperEngine(app.whisperModelManager, ...)` type-checks in Task 6. No cast, no `open` class, no mocking framework.

- [ ] **Step 2: Run it → expected FAIL.**

Command: `.\gradlew.bat testDebugUnitTest --tests "com.whispereverywhere.transcription.LocalWhisperEngineTest"`

Expected failure: compilation error — `Unresolved reference: TranscriptionEngine`, `Unresolved reference: WhisperBackend`, `Unresolved reference: LocalWhisperEngine` (the `transcription` package has no source files yet).

- [ ] **Step 3: Implement `TranscriptionEngine.kt` (interfaces + backends).**

Create `app/src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt`:

```kotlin
package com.whispereverywhere.transcription

import com.whispereverywhere.whisper.WhisperNative

/**
 * Backend-neutral streaming transcription contract. The recorder drives an engine via
 * connect / sendAudio / commit / close and receives results through [Listener].
 */
interface TranscriptionEngine {
    /** Prepare/load the session for [language] (null = auto). Calls onOpen when ready. */
    fun connect(language: String?, listener: Listener)

    /** Buffer one chunk of PCM16 mono @16kHz. Called rapidly from the recorder thread. */
    fun sendAudio(pcm: ByteArray)

    /** Transcribe everything buffered since the last commit, now. */
    fun commit()

    /** Release the session (cancel pending work). */
    fun close()

    interface Listener {
        fun onOpen()
        fun onDelta(text: String)     // unused on-device; kept for interface compatibility
        fun onCompleted(text: String) // final transcript for one committed segment
        fun onError(message: String)
        fun onClosed()
    }
}

/** Thin seam over the native layer so the engine can be tested without JNI. */
interface WhisperBackend {
    fun load(modelPath: String): Long
    fun transcribe(ctx: Long, samples: FloatArray, lang: String?): String
    fun release(ctx: Long)
}

/** Production backend: delegates to [WhisperNative] with translate = false. */
object WhisperNativeBackend : WhisperBackend {
    override fun load(modelPath: String): Long = WhisperNative.init(modelPath)
    override fun transcribe(ctx: Long, samples: FloatArray, lang: String?): String =
        WhisperNative.transcribe(ctx, samples, lang, translate = false)
    override fun release(ctx: Long) = WhisperNative.free(ctx)
}

/**
 * Narrow seam the engine uses to resolve the installed model path. Task 5's
 * WhisperModelManager implements this, so LocalWhisperEngine depends only on this
 * interface (not on Task 5) and its unit tests need no Android Context.
 */
interface ModelPathProvider {
    fun installedModelPath(): String?
}
```

- [ ] **Step 4: Implement `LocalWhisperEngine.kt`.**

Create `app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt`:

```kotlin
package com.whispereverywhere.transcription

import com.whispereverywhere.util.AudioMath
import com.whispereverywhere.util.RetryPolicy
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * On-device whisper.cpp engine. Buffers PCM16 audio, and on commit runs one batch
 * transcription of the buffered segment on a single-thread executor (segments serialize).
 * No intra-segment deltas are emitted — one onCompleted per committed segment.
 */
class LocalWhisperEngine(
    private val modelPathProvider: ModelPathProvider,
    private val retry: RetryPolicy = RetryPolicy(maxAttempts = 3),
    private val backend: WhisperBackend = WhisperNativeBackend,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) : TranscriptionEngine {

    // Model load is retried once (transient FS/mmap) using the injected policy's timing.
    private val loadRetry = RetryPolicy(
        maxAttempts = 2,
        baseDelayMs = retry.baseDelayMs,
        maxDelayMs = retry.maxDelayMs,
        rng = retry.rng,
    )

    private val bufferLock = Any()
    private val buffer = ByteArrayOutputStream()

    @Volatile private var listener: TranscriptionEngine.Listener? = null
    @Volatile private var language: String? = null

    // Process-lifetime cached native context (0 = not loaded).
    @Volatile private var ctxPtr: Long = 0L

    override fun connect(language: String?, listener: TranscriptionEngine.Listener) {
        this.listener = listener
        this.language = language

        val modelPath = modelPathProvider.installedModelPath()
        if (modelPath == null) {
            listener.onError("No speech model installed")
            return
        }

        executor.execute {
            try {
                if (ctxPtr == 0L) {
                    // Retry a transient load failure once before giving up.
                    val loaded = runBlocking { loadRetry.retry { backend.load(modelPath) } }
                    if (loaded == 0L) {
                        listener.onError("Failed to load speech model (may be corrupt - re-download)")
                        return@execute
                    }
                    ctxPtr = loaded
                }
                listener.onOpen()
            } catch (t: Throwable) {
                listener.onError(t.message ?: "Model load failed")
            }
        }
    }

    override fun sendAudio(pcm: ByteArray) {
        synchronized(bufferLock) { buffer.write(pcm) }
    }

    override fun commit() {
        val listener = this.listener ?: return
        val lang = this.language

        // Atomically snapshot + clear the buffer.
        val pcm: ByteArray = synchronized(bufferLock) {
            val snapshot = buffer.toByteArray()
            buffer.reset()
            snapshot
        }
        if (pcm.isEmpty()) return

        executor.execute {
            try {
                val ctx = ctxPtr
                if (ctx == 0L) {
                    listener.onError("Speech model not loaded")
                    return@execute
                }
                val samples = AudioMath.pcm16ToFloat(pcm)
                val text = runBlocking {
                    retry.retry { backend.transcribe(ctx, samples, lang) }
                }
                val trimmed = text.trim()
                if (trimmed.isNotBlank()) {
                    listener.onCompleted(trimmed)
                }
            } catch (t: Throwable) {
                listener.onError(t.message ?: "Transcription failed")
            }
        }
    }

    override fun close() {
        // Cancel pending work but keep the cached context for the next session.
        val listener = this.listener
        synchronized(bufferLock) { buffer.reset() }
        this.listener = null
        listener?.onClosed()
    }

    /**
     * Frees the cached native context (e.g. from onTrimMemory under memory pressure).
     * The context reloads lazily on the next connect(). Runs on the executor so it never
     * races an in-flight transcription.
     */
    fun releaseContext() {
        executor.execute {
            val ctx = ctxPtr
            if (ctx != 0L) {
                try {
                    backend.release(ctx)
                } catch (_: Throwable) {
                }
                ctxPtr = 0L
            }
        }
    }
}
```

No cast or subclass hack is needed: the Step 1 test's `FakeModelPathProvider` implements the `ModelPathProvider` interface (defined in `TranscriptionEngine.kt`), and `LocalWhisperEngine`'s constructor takes a `ModelPathProvider`. Task 5's `WhisperModelManager` also implements `ModelPathProvider`, so the real installed-model path is wired in Task 6 — no `open` class, no mocking framework, no `null!!`.

- [ ] **Step 5: Run → expected PASS (no-model test).**

Command: `.\gradlew.bat testDebugUnitTest --tests "com.whispereverywhere.transcription.LocalWhisperEngineTest.connect_withNoInstalledModel_reportsError"`

Expected: `BUILD SUCCESSFUL`, 1 test passing. Assertions confirm `onError("No speech model installed")` fired and `backend.load` was never called.

- [ ] **Step 6: Add the happy-path test (buffer + commit → onCompleted).**

Add to `LocalWhisperEngineTest`:

```kotlin
    @Test
    fun connect_thenBufferAndCommit_emitsCompletedWithBackendText() {
        val backend = FakeWhisperBackend(text = "  hello world  ")
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
            retry = fastRetry(),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        val listener = RecordingListener()

        engine.connect(language = "en", listener = listener)
        assertTrue(listener.opened)
        assertEquals(listOf("/models/pro.bin"), backend.loadCalls)

        engine.sendAudio(pcm)
        engine.sendAudio(pcm)
        engine.commit()

        assertEquals(listOf("hello world"), listener.completed)   // trimmed
        assertTrue(listener.errors.isEmpty())
        assertTrue(listener.deltas.isEmpty())                     // never onDelta
        assertEquals(1, backend.transcribeCalls.size)
        // 8 PCM bytes buffered -> 4 float samples in one snapshot
        assertEquals(4, backend.transcribeCalls[0].second.size)
        assertEquals(42L, backend.transcribeCalls[0].first)       // cached ctx from load()
        assertEquals("en", backend.transcribeCalls[0].third)
    }

    @Test
    fun commit_withEmptyBuffer_doesNothing() {
        val backend = FakeWhisperBackend()
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
            retry = fastRetry(),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)

        engine.commit()

        assertTrue(backend.transcribeCalls.isEmpty())
        assertTrue(listener.completed.isEmpty())
        assertTrue(listener.errors.isEmpty())
    }

    @Test
    fun commit_whenBackendReturnsBlank_skipsCompleted() {
        val backend = FakeWhisperBackend(text = "   ")
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
            retry = fastRetry(),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        engine.sendAudio(pcm)
        engine.commit()

        assertTrue(listener.completed.isEmpty())   // blank result suppressed
        assertTrue(listener.errors.isEmpty())
        assertEquals(1, backend.transcribeCalls.size)
    }
```

- [ ] **Step 7: Run → expected PASS (happy path + edge cases).**

Command: `.\gradlew.bat testDebugUnitTest --tests "com.whispereverywhere.transcription.LocalWhisperEngineTest"`

Expected: `BUILD SUCCESSFUL`, all four tests so far passing. If the trim/blank assertions fail, verify `commit()` uses `text.trim()` and the `isNotBlank()` guard.

- [ ] **Step 8: Add the transient-failure test (retried → onCompleted).**

Add to `LocalWhisperEngineTest`:

```kotlin
    @Test
    fun commit_withTransientFailures_retriesThenCompletes() {
        // Fail twice, succeed on the 3rd attempt (maxAttempts = 3).
        val backend = FakeWhisperBackend(text = "recovered", failTimes = 2)
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
            retry = fastRetry(),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        engine.sendAudio(pcm)
        engine.commit()

        assertEquals(listOf("recovered"), listener.completed)
        assertTrue(listener.errors.isEmpty())
        assertEquals(3, backend.transcribeCalls.size)   // 1 + 2 retries
    }
```

- [ ] **Step 9: Run → expected PASS (retry succeeds).**

Command: `.\gradlew.bat testDebugUnitTest --tests "com.whispereverywhere.transcription.LocalWhisperEngineTest.commit_withTransientFailures_retriesThenCompletes"`

Expected: `BUILD SUCCESSFUL`. Confirms `retry.retry { backend.transcribe(...) }` re-runs the transcribe closure on the executor and reuses the same snapshot (`samples`) across attempts.

- [ ] **Step 10: Add the permanent-failure test (retries exhausted → onError).**

Add to `LocalWhisperEngineTest`:

```kotlin
    @Test
    fun commit_withPermanentFailure_reportsErrorAfterRetriesExhausted() {
        // Fail more times than maxAttempts (3) -> never succeeds.
        val backend = FakeWhisperBackend(text = "never", failTimes = 99)
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
            retry = fastRetry(),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        engine.sendAudio(pcm)
        engine.commit()

        assertTrue(listener.completed.isEmpty())
        assertEquals(1, listener.errors.size)
        assertEquals("transient transcribe failure", listener.errors[0])
        assertEquals(3, backend.transcribeCalls.size)   // exactly maxAttempts, then give up
    }

    @Test
    fun close_emitsClosedAndClearsBuffer() {
        val backend = FakeWhisperBackend()
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
            retry = fastRetry(),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        engine.sendAudio(pcm)
        engine.close()

        assertTrue(listener.closed)

        // After close, a commit with a stale buffer must not transcribe (buffer was cleared,
        // and the listener was detached).
        engine.commit()
        assertTrue(backend.transcribeCalls.isEmpty())
    }
```

- [ ] **Step 11: Run → expected PASS (full suite).**

Command: `.\gradlew.bat testDebugUnitTest --tests "com.whispereverywhere.transcription.LocalWhisperEngineTest"`

Expected: `BUILD SUCCESSFUL`, all seven tests passing. If `commit_withPermanentFailure...` sees more than 3 transcribe calls, verify `RetryPolicy.retry` honors `maxAttempts = 3` and that `fastRetry()` passes `maxAttempts = 3`.

- [ ] **Step 12: Commit.**

```
git add app/src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt app/src/test/java/com/whispereverywhere/transcription/LocalWhisperEngineTest.kt
git commit -m "Task 4: TranscriptionEngine + LocalWhisperEngine (on-device whisper, single-thread executor, retry-wrapped commit)"
```

**Cross-task note (to Task 5):** `WhisperModelManager` must implement `ModelPathProvider` — i.e. `class WhisperModelManager(...) : ModelPathProvider` with `override fun installedModelPath()`. No `open` class or mocking framework is needed; Task 4's tests use the standalone `FakeModelPathProvider`.

---

### Task 5: WhisperModel Catalog + WhisperModelManager + selectedModelId pref

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/model/WhisperModel.kt`
- Create: `app/src/main/java/com/whispereverywhere/model/WhisperModelManager.kt`
- Modify: `app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt` (add `var selectedModelId: String?`; add `KEY_SELECTED_MODEL_ID` const to the `companion object`)
- Test: `app/src/test/java/com/whispereverywhere/model/WhisperModelTest.kt`
- Test: `app/src/test/java/com/whispereverywhere/model/WhisperCatalogHelpersTest.kt`

**Interfaces:**
- Consumes: `PreferencesManager(context)` with new `var selectedModelId: String?` (key `"selected_model_id"`, default `null`).
- Produces (relied on by Task 4 `LocalWhisperEngine`):
  - `enum class ModelScope { ENGLISH, MULTILINGUAL }`
  - `data class WhisperModel(val id, val displayName, val fileName, val url, val approxBytes: Long, val sha256: String, val scope: ModelScope, val minRamBytes: Long)`
  - `class WhisperModelManager(context: Context, prefs: PreferencesManager)` with `catalog`, `modelById(id)`, `modelsDir()`, `isInstalled(model)`, `installedModel()`, `installedModelPath()`, `deviceTotalRamBytes()`, `isRecommendedForDevice(model)`, `suspend download(model, onProgress)`, `delete(model)`.
  - Pure testable helpers in `WhisperModel.kt` companion / top-level `object WhisperCatalog`: `WhisperCatalog.entries`, `WhisperCatalog.byId(id)`, `WhisperCatalog.isRecommendedForDevice(model, totalRamBytes)`, `WhisperCatalog.sizeWithinTolerance(actualBytes, approxBytes)`, `WhisperCatalog.sha256Hex(bytes)`.

> **Split rationale:** All decision logic (catalog contents, id/scope/minRam, RAM recommendation, size tolerance, sha256 hashing) lives in the framework-free `object WhisperCatalog` so it is unit-testable on the JVM with **no Android dependency**. `WhisperModelManager` is a thin Android shell that delegates to `WhisperCatalog` and only adds `Context`/`ActivityManager`/`DownloadManager`/`File` I/O. The `download(...)` / file-write path uses real code below but is verified by an **instrumented test** (requires `DownloadManager` + real `filesDir`), not a JVM unit test — noted at that step.

---

- [ ] **Step 1: Fill the sha256 constants (data-gathering, do before Step 6).**
  For each catalog `fileName`, compute the real digest and paste the lowercased hex into the corresponding `SHA256_*` const in Step 6. Run from Git Bash:
  ```bash
  for f in ggml-base.en-q5_1.bin ggml-small.en-q5_1.bin ggml-medium.en-q5_0.bin ggml-small-q5_1.bin ggml-large-v3-turbo-q5_0.bin; do
    echo -n "$f  "
    curl -sL "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$f" | sha256sum | cut -d' ' -f1
  done
  ```
  Record the 5 hex strings (64 lowercase hex chars each). **Filling these real digests is a Plan 4 production-hardening step, not a Plan-1 blocker.** Until filled, leave the literal `"PENDING"` — `verify()` (Step 5) treats any non-64-hex constant as *size-gate only*, so downloads are functional now and sha256 enforcement switches on automatically once a real digest is pinned. The catalog/helper unit tests do not assert sha values.

- [ ] **Step 2: Write the failing pure-catalog test (REAL, COMPLETE).**
  Create `app/src/test/java/com/whispereverywhere/model/WhisperCatalogHelpersTest.kt`:
  ```kotlin
  package com.whispereverywhere.model

  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertFalse
  import org.junit.Assert.assertNull
  import org.junit.Assert.assertTrue
  import org.junit.Test

  class WhisperCatalogHelpersTest {

      @Test
      fun catalog_hasFiveEntries_withExpectedIds() {
          val ids = WhisperCatalog.entries.map { it.id }
          assertEquals(5, WhisperCatalog.entries.size)
          assertEquals(listOf("eco", "pro", "extreme", "multi", "ultra"), ids)
      }

      @Test
      fun catalog_scopesAndMinRam_areCorrect() {
          fun m(id: String) = WhisperCatalog.byId(id)!!

          assertEquals(ModelScope.ENGLISH, m("eco").scope)
          assertEquals(0L, m("eco").minRamBytes)

          assertEquals(ModelScope.ENGLISH, m("pro").scope)
          assertEquals(0L, m("pro").minRamBytes)

          assertEquals(ModelScope.ENGLISH, m("extreme").scope)
          assertEquals(6_000_000_000L, m("extreme").minRamBytes)

          assertEquals(ModelScope.MULTILINGUAL, m("multi").scope)
          assertEquals(0L, m("multi").minRamBytes)

          assertEquals(ModelScope.MULTILINGUAL, m("ultra").scope)
          assertEquals(8_000_000_000L, m("ultra").minRamBytes)
      }

      @Test
      fun catalog_urlsAndApproxBytes_matchContract() {
          val base = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
          val eco = WhisperCatalog.byId("eco")!!
          assertEquals("ggml-base.en-q5_1.bin", eco.fileName)
          assertEquals(base + "ggml-base.en-q5_1.bin", eco.url)
          assertEquals(57_000_000L, eco.approxBytes)

          assertEquals(190_000_000L, WhisperCatalog.byId("pro")!!.approxBytes)
          assertEquals(539_000_000L, WhisperCatalog.byId("extreme")!!.approxBytes)
          assertEquals(190_000_000L, WhisperCatalog.byId("multi")!!.approxBytes)
          assertEquals(574_000_000L, WhisperCatalog.byId("ultra")!!.approxBytes)
      }

      @Test
      fun modelById_returnsNull_forUnknownId() {
          assertNull(WhisperCatalog.byId("nope"))
      }

      @Test
      fun isRecommended_boundary_atMinRam() {
          val ultra = WhisperCatalog.byId("ultra")!! // minRam 8_000_000_000

          // just below -> not recommended
          assertFalse(WhisperCatalog.isRecommendedForDevice(ultra, 7_999_999_999L))
          // exactly at threshold -> recommended (>=)
          assertTrue(WhisperCatalog.isRecommendedForDevice(ultra, 8_000_000_000L))
          // above -> recommended
          assertTrue(WhisperCatalog.isRecommendedForDevice(ultra, 12_000_000_000L))
      }

      @Test
      fun isRecommended_zeroMinRam_alwaysRecommended() {
          val eco = WhisperCatalog.byId("eco")!!
          assertTrue(WhisperCatalog.isRecommendedForDevice(eco, 0L))
          assertTrue(WhisperCatalog.isRecommendedForDevice(eco, 2_000_000_000L))
      }

      @Test
      fun sizeWithinTolerance_fivePercent() {
          val approx = 100_000_000L
          // exactly equal
          assertTrue(WhisperCatalog.sizeWithinTolerance(100_000_000L, approx))
          // +5% edge (105,000,000) inclusive
          assertTrue(WhisperCatalog.sizeWithinTolerance(105_000_000L, approx))
          // -5% edge (95,000,000) inclusive
          assertTrue(WhisperCatalog.sizeWithinTolerance(95_000_000L, approx))
          // just over +5%
          assertFalse(WhisperCatalog.sizeWithinTolerance(105_000_001L, approx))
          // just under -5%
          assertFalse(WhisperCatalog.sizeWithinTolerance(94_999_999L, approx))
      }
  }
  ```

- [ ] **Step 3: Write the failing sha256 known-vector test (REAL, COMPLETE).**
  Create `app/src/test/java/com/whispereverywhere/model/WhisperModelTest.kt`:
  ```kotlin
  package com.whispereverywhere.model

  import org.junit.Assert.assertEquals
  import org.junit.Test

  class WhisperModelTest {

      @Test
      fun sha256Hex_ofEmptyInput_matchesKnownVector() {
          // SHA-256 of the empty byte array (RFC/NIST known vector)
          val expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
          assertEquals(expected, WhisperCatalog.sha256Hex(ByteArray(0)))
      }

      @Test
      fun sha256Hex_ofAbc_matchesKnownVector() {
          // SHA-256("abc") known vector
          val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
          assertEquals(expected, WhisperCatalog.sha256Hex("abc".toByteArray(Charsets.US_ASCII)))
      }

      @Test
      fun sha256Hex_isLowercaseHex_64Chars() {
          val hex = WhisperCatalog.sha256Hex("whisper".toByteArray(Charsets.US_ASCII))
          assertEquals(64, hex.length)
          assertEquals(hex.lowercase(), hex)
          assertEquals(true, hex.all { it in "0123456789abcdef" })
      }
  }
  ```

- [ ] **Step 4: Run the tests -> expected FAIL.**
  ```
  .\gradlew.bat testDebugUnitTest --tests "com.whispereverywhere.model.WhisperCatalogHelpersTest" --tests "com.whispereverywhere.model.WhisperModelTest"
  ```
  Expected failure: compilation error `Unresolved reference: WhisperCatalog` / `Unresolved reference: ModelScope` (the `model` package and helpers do not exist yet).

- [ ] **Step 5: Implement `WhisperModel.kt` (enum + data class + pure `WhisperCatalog`) (REAL, COMPLETE).**
  Create `app/src/main/java/com/whispereverywhere/model/WhisperModel.kt`. No Android imports — fully JVM-testable:
  ```kotlin
  package com.whispereverywhere.model

  import java.security.MessageDigest

  /** Language coverage of a whisper model. */
  enum class ModelScope { ENGLISH, MULTILINGUAL }

  /**
   * A downloadable whisper.cpp model tier.
   *
   * @param approxBytes advertised download size; used as a size gate before sha256 verification.
   * @param sha256 lowercased hex digest of the downloaded file (see WhisperCatalog SHA256_* consts).
   * @param minRamBytes minimum device RAM to *recommend* this model (0 = recommended on any device).
   */
  data class WhisperModel(
      val id: String,
      val displayName: String,
      val fileName: String,
      val url: String,
      val approxBytes: Long,
      val sha256: String,
      val scope: ModelScope,
      val minRamBytes: Long,
  )

  /**
   * Pure, Android-free catalog + decision helpers. Everything here is JVM-unit-testable.
   * The Android shell (WhisperModelManager) delegates to these.
   */
  object WhisperCatalog {

      private const val BASE_URL =
          "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

      /** Allowed deviation of the actual downloaded size from approxBytes before sha256 verify. */
      const val SIZE_TOLERANCE = 0.05

      // sha256 of each file, lowercased hex. Fill via: curl -sL <url> | sha256sum
      // (See the plan "Fill the sha256 constants" step.) "PENDING" until fetched.
      private const val SHA256_ECO = "PENDING"
      private const val SHA256_PRO = "PENDING"
      private const val SHA256_EXTREME = "PENDING"
      private const val SHA256_MULTI = "PENDING"
      private const val SHA256_ULTRA = "PENDING"

      private fun urlFor(fileName: String): String = BASE_URL + fileName

      val entries: List<WhisperModel> = listOf(
          WhisperModel(
              id = "eco",
              displayName = "Eco (base.en)",
              fileName = "ggml-base.en-q5_1.bin",
              url = urlFor("ggml-base.en-q5_1.bin"),
              approxBytes = 57_000_000L,
              sha256 = SHA256_ECO,
              scope = ModelScope.ENGLISH,
              minRamBytes = 0L,
          ),
          WhisperModel(
              id = "pro",
              displayName = "Pro (small.en)",
              fileName = "ggml-small.en-q5_1.bin",
              url = urlFor("ggml-small.en-q5_1.bin"),
              approxBytes = 190_000_000L,
              sha256 = SHA256_PRO,
              scope = ModelScope.ENGLISH,
              minRamBytes = 0L,
          ),
          WhisperModel(
              id = "extreme",
              displayName = "Extreme (medium.en)",
              fileName = "ggml-medium.en-q5_0.bin",
              url = urlFor("ggml-medium.en-q5_0.bin"),
              approxBytes = 539_000_000L,
              sha256 = SHA256_EXTREME,
              scope = ModelScope.ENGLISH,
              minRamBytes = 6_000_000_000L,
          ),
          WhisperModel(
              id = "multi",
              displayName = "Multilingual (small)",
              fileName = "ggml-small-q5_1.bin",
              url = urlFor("ggml-small-q5_1.bin"),
              approxBytes = 190_000_000L,
              sha256 = SHA256_MULTI,
              scope = ModelScope.MULTILINGUAL,
              minRamBytes = 0L,
          ),
          WhisperModel(
              id = "ultra",
              displayName = "Ultra (large-v3-turbo)",
              fileName = "ggml-large-v3-turbo-q5_0.bin",
              url = urlFor("ggml-large-v3-turbo-q5_0.bin"),
              approxBytes = 574_000_000L,
              sha256 = SHA256_ULTRA,
              scope = ModelScope.MULTILINGUAL,
              minRamBytes = 8_000_000_000L,
          ),
      )

      /** Default tier selected on first run (Pro / small.en). */
      const val DEFAULT_MODEL_ID = "pro"

      fun byId(id: String?): WhisperModel? = entries.firstOrNull { it.id == id }

      /** A model is recommended when the device has at least its minimum RAM. */
      fun isRecommendedForDevice(model: WhisperModel, totalRamBytes: Long): Boolean =
          totalRamBytes >= model.minRamBytes

      /**
       * Size gate: is [actualBytes] within +/-SIZE_TOLERANCE of [approxBytes]?
       * Inclusive at both +/-5% edges. Runs before the sha256 compare in verify().
       */
      fun sizeWithinTolerance(actualBytes: Long, approxBytes: Long): Boolean {
          val delta = Math.abs(actualBytes - approxBytes).toDouble()
          val allowed = approxBytes.toDouble() * SIZE_TOLERANCE
          return delta <= allowed
      }

      /** Lowercased hex SHA-256 of [bytes]. */
      fun sha256Hex(bytes: ByteArray): String {
          val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
          val sb = StringBuilder(digest.size * 2)
          for (b in digest) {
              val v = b.toInt() and 0xFF
              sb.append("0123456789abcdef"[v ushr 4])
              sb.append("0123456789abcdef"[v and 0x0F])
          }
          return sb.toString()
      }

      /**
       * verify(): size-gate first (cheap, avoids hashing a truncated file), THEN sha256 compare.
       * Returns true only when both pass. [expectedSha256] is compared case-insensitively.
       */
      fun verify(actualBytes: Long, approxBytes: Long, fileBytes: ByteArray, expectedSha256: String): Boolean {
          if (!sizeWithinTolerance(actualBytes, approxBytes)) return false
          // The sha256 gate is enforced only once the real digest is pinned (a Plan 4 production
          // step fills these). Until then the constant is "PENDING" and we accept on the size gate,
          // so Plan-1 downloads are functional now; sha enforcement switches on automatically.
          val expected = expectedSha256.trim().lowercase()
          val isRealDigest = expected.length == 64 && expected.all { it in "0123456789abcdef" }
          if (!isRealDigest) return true
          return sha256Hex(fileBytes).equals(expected, ignoreCase = true)
      }
  }
  ```

- [ ] **Step 6: Run the tests -> expected PASS.**
  ```
  .\gradlew.bat testDebugUnitTest --tests "com.whispereverywhere.model.WhisperCatalogHelpersTest" --tests "com.whispereverywhere.model.WhisperModelTest"
  ```
  Expected: `BUILD SUCCESSFUL`, all 12 test methods pass (5 catalog + 3 sha256 + others).

- [ ] **Step 7: Commit the pure layer.**
  ```
  git add "app/src/main/java/com/whispereverywhere/model/WhisperModel.kt" "app/src/test/java/com/whispereverywhere/model/WhisperCatalogHelpersTest.kt" "app/src/test/java/com/whispereverywhere/model/WhisperModelTest.kt"
  git commit -m "Add WhisperModel catalog + pure JVM-testable helpers (catalog, sha256, size tolerance, RAM gate)"
  ```

- [ ] **Step 8: Add `selectedModelId` to `PreferencesManager` (REAL, COMPLETE edit).**
  In `app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt`, add a `var` property just after the existing `onboardingCompleted` block (line ~129), matching the file's plain-`prefs`/`edit().apply()` style:
  ```kotlin
      // Selected on-device whisper model tier id (see WhisperCatalog); null = none chosen yet
      var selectedModelId: String?
          get() = prefs.getString(KEY_SELECTED_MODEL_ID, null)
          set(value) {
              prefs.edit().putString(KEY_SELECTED_MODEL_ID, value).apply()
          }
  ```
  And add the key constant inside the `companion object`, next to `KEY_ONBOARDING_COMPLETED`:
  ```kotlin
          private const val KEY_SELECTED_MODEL_ID = "selected_model_id"
  ```

- [ ] **Step 9: Implement `WhisperModelManager.kt` (Android shell) (REAL, COMPLETE).**
  Create `app/src/main/java/com/whispereverywhere/model/WhisperModelManager.kt`. Pure logic is delegated to `WhisperCatalog`; the DownloadManager/File I/O path is real (verified by instrumented test, Step 11):
  ```kotlin
  package com.whispereverywhere.model

  import android.app.ActivityManager
  import android.app.DownloadManager
  import android.content.Context
  import android.database.Cursor
  import android.net.Uri
  import android.os.Environment
  import androidx.core.net.toUri
  import com.whispereverywhere.data.local.PreferencesManager
  import com.whispereverywhere.transcription.ModelPathProvider
  import kotlinx.coroutines.delay
  import java.io.File

  /**
   * Android shell over [WhisperCatalog]. Holds no decision logic beyond framework wiring
   * (Context/ActivityManager/DownloadManager/File). All pure logic lives in [WhisperCatalog]
   * and is unit-tested there; download()/verify-on-disk is covered by an instrumented test.
   */
  class WhisperModelManager(
      private val context: Context,
      private val prefs: PreferencesManager,
  ) : ModelPathProvider {

      val catalog: List<WhisperModel> = WhisperCatalog.entries

      fun modelById(id: String): WhisperModel? = WhisperCatalog.byId(id)

      /** context.filesDir/models, created if missing. */
      fun modelsDir(): File {
          val dir = File(context.filesDir, "models")
          if (!dir.exists()) dir.mkdirs()
          return dir
      }

      private fun fileFor(model: WhisperModel): File = File(modelsDir(), model.fileName)

      /** Installed = file present on disk with a size within tolerance of approxBytes. */
      fun isInstalled(model: WhisperModel): Boolean {
          val f = fileFor(model)
          return f.exists() && WhisperCatalog.sizeWithinTolerance(f.length(), model.approxBytes)
      }

      /** The selected model, if it is actually installed on disk. */
      fun installedModel(): WhisperModel? {
          val model = WhisperCatalog.byId(prefs.selectedModelId) ?: return null
          return if (isInstalled(model)) model else null
      }

      /** Absolute path to the installed selected model file, or null. */
      override fun installedModelPath(): String? {
          val model = installedModel() ?: return null
          return fileFor(model).absolutePath
      }

      /** Total device RAM in bytes (ActivityManager.MemoryInfo.totalMem). */
      fun deviceTotalRamBytes(): Long {
          val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
          val info = ActivityManager.MemoryInfo()
          am.getMemoryInfo(info)
          return info.totalMem
      }

      /** True when this device has at least the model's recommended RAM. */
      fun isRecommendedForDevice(model: WhisperModel): Boolean =
          WhisperCatalog.isRecommendedForDevice(model, deviceTotalRamBytes())

      /**
       * Download [model] via Android DownloadManager into modelsDir, reporting (soFar, total)
       * as it progresses. On completion, size-gate + sha256 verify; delete + throw on mismatch.
       * Suspends until the download terminates. (Instrumented test only — needs DownloadManager.)
       */
      suspend fun download(model: WhisperModel, onProgress: (soFar: Long, total: Long) -> Unit) {
          val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
          val dest = fileFor(model)
          if (dest.exists()) dest.delete()

          val request = DownloadManager.Request(model.url.toUri())
              .setTitle(model.displayName)
              .setDescription("Downloading speech model")
              .setDestinationInExternalFilesDir(
                  context,
                  Environment.DIRECTORY_DOWNLOADS,
                  "models/${model.fileName}",
              )
              .setNotificationVisibility(
                  DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
              )
              .setAllowedOverMetered(true)
              .setAllowedOverRoaming(true)

          val id = dm.enqueue(request)
          try {
              var done = false
              while (!done) {
                  val query = DownloadManager.Query().setFilterById(id)
                  val cursor: Cursor = dm.query(query)
                  cursor.use { c ->
                      if (c.moveToFirst()) {
                          val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                          val soFar = c.getLong(
                              c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                          )
                          val total = c.getLong(
                              c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                          )
                          onProgress(soFar, if (total > 0) total else model.approxBytes)

                          when (status) {
                              DownloadManager.STATUS_SUCCESSFUL -> {
                                  val localUri = c.getString(
                                      c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI),
                                  )
                                  moveToModelsDir(localUri, dest)
                                  done = true
                              }
                              DownloadManager.STATUS_FAILED -> {
                                  val reason = c.getInt(
                                      c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON),
                                  )
                                  throw ModelDownloadException("Download failed (reason=$reason)")
                              }
                              else -> Unit // PENDING / RUNNING / PAUSED -> keep polling
                          }
                      } else {
                          // Row gone (e.g. user cancelled via notification).
                          throw ModelDownloadException("Download entry disappeared")
                      }
                  }
                  if (!done) delay(POLL_INTERVAL_MS)
              }
          } finally {
              // The DownloadManager row is no longer needed once we've moved the file.
              dm.remove(id)
          }

          // Verify on disk: size gate THEN sha256. Delete + fail on mismatch.
          val actualLen = dest.length()
          val bytes = dest.readBytes()
          val ok = WhisperCatalog.verify(actualLen, model.approxBytes, bytes, model.sha256)
          if (!ok) {
              dest.delete()
              throw ModelDownloadException("Verification failed for ${model.fileName}")
          }
      }

      private fun moveToModelsDir(localUri: String?, dest: File) {
          val src = localUri?.let { File(Uri.parse(it).path ?: return@let null) }
          if (src != null && src.exists()) {
              if (dest.exists()) dest.delete()
              if (!src.renameTo(dest)) {
                  src.copyTo(dest, overwrite = true)
                  src.delete()
              }
          }
      }

      /** Remove the installed file for [model] (no-op if absent). */
      fun delete(model: WhisperModel) {
          val f = fileFor(model)
          if (f.exists()) f.delete()
      }

      class ModelDownloadException(message: String) : Exception(message)

      companion object {
          private const val POLL_INTERVAL_MS = 300L
      }
  }
  ```

- [ ] **Step 10: Compile the Android shell -> expected PASS.**
  ```
  .\gradlew.bat assembleDebug
  ```
  Expected: `BUILD SUCCESSFUL` (verifies `WhisperModelManager`, the `PreferencesManager.selectedModelId` edit, and `androidx.core.net.toUri`/DownloadManager wiring compile against the app module). JVM unit tests still pass since the pure layer is unchanged.

- [ ] **Step 11: Add the instrumented download/verify test (REAL, COMPLETE) — noted as instrumented, not JVM.**
  The `download()` + on-disk verify + `modelsDir()`/`isInstalled()`/`installedModelPath()` paths need a real `DownloadManager`, `filesDir`, and network, so they run as an **androidTest** (`connectedDebugAndroidTest`), not `testDebugUnitTest`. Create `app/src/androidTest/java/com/whispereverywhere/model/WhisperModelManagerInstrumentedTest.kt`:
  ```kotlin
  package com.whispereverywhere.model

  import androidx.test.ext.junit.runners.AndroidJUnit4
  import androidx.test.platform.app.InstrumentationRegistry
  import com.whispereverywhere.data.local.PreferencesManager
  import kotlinx.coroutines.runBlocking
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertNotNull
  import org.junit.Assert.assertNull
  import org.junit.Assert.assertTrue
  import org.junit.Before
  import org.junit.Test
  import org.junit.runner.RunWith

  @RunWith(AndroidJUnit4::class)
  class WhisperModelManagerInstrumentedTest {

      private lateinit var manager: WhisperModelManager
      private lateinit var prefs: PreferencesManager

      @Before
      fun setUp() {
          val ctx = InstrumentationRegistry.getInstrumentation().targetContext
          prefs = PreferencesManager(ctx)
          manager = WhisperModelManager(ctx, prefs)
          // Clean any leftover models from previous runs.
          manager.catalog.forEach { manager.delete(it) }
          prefs.selectedModelId = null
      }

      @Test
      fun modelsDir_isCreatedUnderFilesDir() {
          val dir = manager.modelsDir()
          assertTrue(dir.exists())
          assertTrue(dir.absolutePath.endsWith("/models"))
      }

      @Test
      fun installedModel_isNull_whenNothingSelectedOrDownloaded() {
          assertNull(manager.installedModel())
          assertNull(manager.installedModelPath())
      }

      @Test
      fun deviceRam_isPositive() {
          assertTrue(manager.deviceTotalRamBytes() > 0L)
      }

      @Test
      fun download_eco_thenInstalledAndPathResolves() = runBlocking {
          val eco = manager.modelById("eco")!!
          manager.download(eco) { soFar, total ->
              assertTrue(total > 0)
              assertTrue(soFar in 0..total)
          }
          prefs.selectedModelId = "eco"

          assertTrue(manager.isInstalled(eco))
          assertEquals(eco.id, manager.installedModel()?.id)
          val path = manager.installedModelPath()
          assertNotNull(path)
          assertTrue(path!!.endsWith(eco.fileName))
      }
  }
  ```
  Run (requires a connected device/emulator with network, and the real `SHA256_ECO` from Step 1 filled in):
  ```
  .\gradlew.bat connectedDebugAndroidTest --tests "com.whispereverywhere.model.WhisperModelManagerInstrumentedTest"
  ```
  Expected: PASS on a device (downloads ~57 MB Eco, verifies size+sha, resolves the installed path). This test is **not** part of the `testDebugUnitTest` gate — the JVM CI gate covers only the pure `WhisperCatalog` logic.

- [ ] **Step 12: Commit the Android shell + pref + instrumented test.**
  ```
  git add "app/src/main/java/com/whispereverywhere/model/WhisperModelManager.kt" "app/src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt" "app/src/androidTest/java/com/whispereverywhere/model/WhisperModelManagerInstrumentedTest.kt"
  git commit -m "Add WhisperModelManager (DownloadManager+verify), selectedModelId pref, instrumented download test"
  ```

---

### Task 6: Wire FloatingBubbleService to LocalWhisperEngine (remove cloud path)

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/WhisperEverywhereApp.kt` (add a lazy `whisperModelManager` property)
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` (`import`s, the `realtimeClient` field, `startRecording()`, `stopRecording()`, `teardownRealtime()`, `onDestroy()`)
- Modify: `app/build.gradle.kts` (remove the two OkHttp dependency lines)
- Delete: `app/src/main/java/com/whispereverywhere/data/api/RealtimeTranscriptionClient.kt`
- Delete: `app/src/main/java/com/whispereverywhere/data/api/RealtimeEvents.kt`
- Delete: `app/src/test/java/com/whispereverywhere/RealtimeEventsTest.kt`
- Test: none (service wiring — no JVM unit test; verified by `assembleDebug` + on-device checklist)

**Interfaces:**
- Consumes (from Task 4):
  - `interface TranscriptionEngine { fun connect(language: String?, listener: Listener); fun sendAudio(pcm: ByteArray); fun commit(); fun close() }`
  - `TranscriptionEngine.Listener { fun onOpen(); fun onDelta(text: String); fun onCompleted(text: String); fun onError(message: String); fun onClosed() }`
  - `class LocalWhisperEngine(modelPathProvider: ModelPathProvider, retry: RetryPolicy = RetryPolicy(maxAttempts = 3), backend: WhisperBackend = WhisperNativeBackend) : TranscriptionEngine` — `WhisperModelManager` implements `ModelPathProvider`, so `LocalWhisperEngine(app.whisperModelManager)` type-checks. Also exposes `fun releaseContext()`.
  - `LocalWhisperEngine.connect()` calls `listener.onError("No speech model installed")` when `installedModelPath()` is null.
- Consumes (from Task 5):
  - `class WhisperModelManager(context: Context, prefs: PreferencesManager)` with `installedModelPath(): String?`
- Consumes (existing):
  - `PreferencesManager.getLanguageForApi(): String?`, `WhisperEverywhereApp.preferencesManager`, `WhisperEverywhereApp.getInstance()`
- Produces (later tasks / onboarding rely on):
  - `WhisperEverywhereApp.whisperModelManager: WhisperModelManager` (lazy, process-lifetime singleton)

Because this task removes the last consumer of OkHttp and `RealtimeEvents`, it must run **after** Tasks 4 and 5 (which create `TranscriptionEngine`, `LocalWhisperEngine`, `WhisperModelManager`); otherwise the module will not compile.

> **Scope of Plan 1:** this delivers the on-device engine, verified via the instrumented tests (Task 1 smoke, Task 5 download) and a **sideloaded** model (Step 9). In-app tiered model **download & onboarding is Plan 2** — until then the app is not end-to-end UI-runnable (a fresh user with no model gets the "open the app to download one" error), which is the expected Plan-1 end state.

---

- [ ] **Step 1: Expose a lazy `whisperModelManager` on `WhisperEverywhereApp`.**
  Read confirms `preferencesManager` is initialized in `onCreate()` before any service can start. Add a lazily-initialized manager so the first `LocalWhisperEngine` construction loads the catalog/prefs once and is cached for process lifetime. Edit `WhisperEverywhereApp.kt`.

  Add the import (after the existing `PreferencesManager` import):
  ```kotlin
  import com.whispereverywhere.data.local.PreferencesManager
  import com.whispereverywhere.data.local.UsageTracker
  import com.whispereverywhere.model.WhisperModelManager
  ```

  Add the property (right after the `usageTracker` declaration block, before `override fun onCreate()`):
  ```kotlin
      /**
       * Process-lifetime model manager. Lazy so it is created on first use
       * (first recording / onboarding) after [preferencesManager] is initialized.
       */
      val whisperModelManager: WhisperModelManager by lazy {
          WhisperModelManager(this, preferencesManager)
      }
  ```

- [ ] **Step 2: Swap the `RealtimeTranscriptionClient` field for a `TranscriptionEngine`.**
  Edit `FloatingBubbleService.kt`. Replace the cloud import with the engine imports.

  Replace the import line:
  ```kotlin
  import com.whispereverywhere.data.api.RealtimeTranscriptionClient
  ```
  with:
  ```kotlin
  import com.whispereverywhere.transcription.LocalWhisperEngine
  import com.whispereverywhere.transcription.TranscriptionEngine
  ```

  Replace the field declaration:
  ```kotlin
      private var realtimeClient: RealtimeTranscriptionClient? = null
  ```
  with:
  ```kotlin
      private var transcriptionEngine: TranscriptionEngine? = null
  ```

- [ ] **Step 3: Rewrite `startRecording()` to drive `LocalWhisperEngine`.**
  Keep the same call sites (`connect(language, listener)` / `sendAudio` / `commit` / `close`) and unchanged `Listener` callback semantics (`onOpen` starts the recorder, `onCompleted` -> `handleTranscriptionResult`). Drop the API-key gate (transcription is on-device). Map the "no model installed" error path to `ERROR` + a toast that tells the user to open onboarding; the existing `CONNECTING` state already covers the model-load wait because `LocalWhisperEngine.connect()` loads the native context off-thread and only calls `onOpen()` once ready.

  Replace the entire `startRecording()` function (lines 564–659) with:
  ```kotlin
      private fun startRecording() {
          if (!audioRecorder.hasPermission()) {
              vibrateError(); showToast("Microphone permission required"); return
          }

          updateBubbleState(BubbleState.CONNECTING)
          vibrateStart()

          // On-device engine. connect() resolves the installed model and loads the
          // native context off-thread; CONNECTING covers that model-load wait and
          // onOpen() fires only once the context is ready.
          val engine: TranscriptionEngine = LocalWhisperEngine(app.whisperModelManager)
          transcriptionEngine = engine

          engine.connect(app.preferencesManager.getLanguageForApi(), object : TranscriptionEngine.Listener {
              override fun onOpen() {
                  serviceScope.launch(Dispatchers.Main) {
                      if (currentState != BubbleState.CONNECTING) return@launch
                      val started = audioRecorder.start { chunk -> engine.sendAudio(chunk) }
                      if (started.isFailure) {
                          showToast("Recording failed: ${started.exceptionOrNull()?.message}")
                          teardownRealtime(); updateBubbleState(BubbleState.ERROR)
                          return@launch
                      }
                      speechSegmenter.reset()
                      sessionTranscription.clear()

                      // Show preview text bubble if we are not injecting into a text field
                      if (currentContext != BubbleContext.TEXT_FIELD) {
                          transcriptionEditText.text = ""
                          transcriptionDeltaText.text = ""
                          transcriptionDeltaText.visibility = View.GONE
                          transcriptionPreviewContainer.visibility = View.VISIBLE
                      } else {
                          transcriptionPreviewContainer.visibility = View.GONE
                      }

                      updateBubbleState(BubbleState.RECORDING)
                      amplitudeJob = serviceScope.launch {
                          audioRecorder.amplitude.collectLatest { amp ->
                              if (currentState != BubbleState.RECORDING) return@collectLatest
                              waveformView.updateAmplitude(amp)
                              // Client VAD: commit on a natural pause (or max segment) so each
                              // utterance is transcribed on-device and injected per segment.
                              if (speechSegmenter.onAmplitude(amp, System.currentTimeMillis())) {
                                  transcriptionEngine?.commit()
                              }
                          }
                      }
                  }
              }
              override fun onDelta(text: String) {
                  // On-device engine emits no intra-segment deltas; kept for interface parity.
                  if (currentContext != BubbleContext.TEXT_FIELD) {
                      serviceScope.launch(Dispatchers.Main) {
                          if (text.isNotBlank()) {
                              transcriptionDeltaText.visibility = View.VISIBLE
                              transcriptionDeltaText.text = text
                          } else {
                              transcriptionDeltaText.visibility = View.GONE
                          }
                      }
                  }
              }
              override fun onCompleted(text: String) {
                  val trimmed = text.trim()
                  if (trimmed.isNotEmpty()) {
                      serviceScope.launch(Dispatchers.Main) {
                          if (currentContext != BubbleContext.TEXT_FIELD) {
                              transcriptionDeltaText.visibility = View.GONE
                              val currentText = transcriptionEditText.text.toString()
                              if (currentText.isNotEmpty() && !currentText.last().isWhitespace() && !trimmed.first().isWhitespace()) {
                                  transcriptionEditText.append(" $trimmed")
                              } else {
                                  transcriptionEditText.append(trimmed)
                              }

                              // Auto-scroll to bottom using standard TextView logic
                              val scrollAmount = transcriptionEditText.layout.getLineTop(transcriptionEditText.lineCount) - transcriptionEditText.height
                              if (scrollAmount > 0) {
                                  transcriptionEditText.scrollTo(0, scrollAmount)
                              } else {
                                  transcriptionEditText.scrollTo(0, 0)
                              }
                          }
                          handleTranscriptionResult(trimmed)
                      }
                  }
              }
              override fun onError(message: String) {
                  serviceScope.launch(Dispatchers.Main) {
                      // "No speech model installed" surfaces here when no model is present;
                      // point the user at onboarding to download one.
                      val userMessage = if (message.contains("model", ignoreCase = true)) {
                          "No speech model installed — open the app to download one"
                      } else {
                          message
                      }
                      showToast(userMessage)
                      teardownRealtime()
                      updateBubbleState(BubbleState.ERROR)
                  }
              }
              override fun onClosed() { /* expected on manual stop */ }
          })
      }
  ```

- [ ] **Step 4: Update `stopRecording()`, `teardownRealtime()`, and `onDestroy()` to reference the engine.**
  These three sites still name the old `realtimeClient`. Update the commit call in `stopRecording()`:

  Replace (inside `stopRecording()`):
  ```kotlin
          // Flush any speech captured since the last pause-commit.
          if (speechSegmenter.hasPendingSpeech()) {
              realtimeClient?.commit()
          }
  ```
  with:
  ```kotlin
          // Flush any speech captured since the last pause-commit.
          if (speechSegmenter.hasPendingSpeech()) {
              transcriptionEngine?.commit()
          }
  ```

  Replace the `teardownRealtime()` function body:
  ```kotlin
      private fun teardownRealtime() {
          realtimeClient?.close()
          realtimeClient = null
      }
  ```
  with (also free the native whisper context so it does not leak across sessions — a new engine
  reloads it lazily on the next `startRecording()`):
  ```kotlin
      private fun teardownRealtime() {
          (transcriptionEngine as? LocalWhisperEngine)?.releaseContext()
          transcriptionEngine?.close()
          transcriptionEngine = null
      }
  ```

  The `onDestroy()` call to `teardownRealtime()` (line 181) is unchanged and now correctly closes the engine (and frees the native context).

  Also add an `onTrimMemory` override so the cached native context is released under memory pressure when idle/backgrounded (it reloads lazily on the next `connect()`). Guard it so it never frees the model out from under an in-progress recording:
  ```kotlin
      override fun onTrimMemory(level: Int) {
          super.onTrimMemory(level)
          if (level >= TRIM_MEMORY_RUNNING_LOW && currentState != BubbleState.RECORDING) {
              (transcriptionEngine as? LocalWhisperEngine)?.releaseContext()
          }
      }
  ```

- [ ] **Step 5: Delete the cloud realtime files.**
  ```bash
  git rm "app/src/main/java/com/whispereverywhere/data/api/RealtimeTranscriptionClient.kt" \
         "app/src/main/java/com/whispereverywhere/data/api/RealtimeEvents.kt" \
         "app/src/test/java/com/whispereverywhere/RealtimeEventsTest.kt"
  ```
  (If not tracked by git, delete on disk instead: `rm "app/src/main/java/com/whispereverywhere/data/api/RealtimeTranscriptionClient.kt" "app/src/main/java/com/whispereverywhere/data/api/RealtimeEvents.kt" "app/src/test/java/com/whispereverywhere/RealtimeEventsTest.kt"`.)

- [ ] **Step 6: Remove the OkHttp dependencies from `app/build.gradle.kts`.**
  A grep over the whole project confirms the only OkHttp/okio references were in `RealtimeTranscriptionClient.kt` (now deleted), so the dependency can be dropped. Edit `app/build.gradle.kts`.

  Replace:
  ```kotlin
      // Networking - OkHttp for Whisper API
      implementation("com.squareup.okhttp3:okhttp:4.12.0")
      implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

      // JSON Serialization
  ```
  with:
  ```kotlin
      // JSON Serialization
  ```

- [ ] **Step 7: Verify — grep must show zero remaining cloud/OkHttp references.**
  ```bash
  git grep -nE "okhttp|OkHttp|RealtimeTranscriptionClient|RealtimeEventFactory|RealtimeEventParser|ServerEvent" -- "app/" ; echo "exit=$?"
  ```
  Expected: no matches (`exit=1`). If any line is printed, fix that reference before continuing.

- [ ] **Step 8: Verify — build the debug variant (compiles + native build).**
  ```
  .\gradlew.bat assembleDebug
  ```
  Expected: `BUILD SUCCESSFUL`. This is the primary gate for this task (no JVM unit test exists for service wiring). If it fails on an unresolved `LocalWhisperEngine` / `TranscriptionEngine` / `WhisperModelManager` symbol, confirm Tasks 4 and 5 are merged first.

- [ ] **Step 9: Manual on-device checklist (record in a text field -> per-utterance injection).**
  In-app download/onboarding is **Plan 2**, so Plan 1's end-to-end manual check uses a **sideloaded**
  model. Put a model in place and select it first (debuggable build):
  ```bash
  curl -L -o ggml-small.en-q5_1.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en-q5_1.bin
  adb push ggml-small.en-q5_1.bin /data/local/tmp/ggml-small.en-q5_1.bin
  adb shell "run-as com.whispereverywhere mkdir -p files/models"
  adb shell "run-as com.whispereverywhere cp /data/local/tmp/ggml-small.en-q5_1.bin files/models/ggml-small.en-q5_1.bin"
  ```
  Set `prefs.selectedModelId = "pro"` via the Task 5 instrumented test helper (which writes the pref)
  or a temporary debug action. With the model in `filesDir/models` and Pro selected, confirm:
  - Focus a text field in another app -> bubble appears; tap it -> bubble enters CONNECTING while the model context loads, then RECORDING.
  - Speak a sentence, pause -> the utterance is injected into the text field on the pause (per-segment `onCompleted`); speak a second sentence -> it is appended after the first.
  - Tap to stop -> any pending speech is flushed and injected, bubble returns to IDLE.
  - Enable airplane mode and repeat -> transcription still works (no network used).
  - With **no** model installed (fresh install / model deleted), tap to record -> bubble goes to ERROR and a toast reads "No speech model installed — open the app to download one".

- [ ] **Step 10: Commit.**
  ```
  git add "app/src/main/java/com/whispereverywhere/WhisperEverywhereApp.kt" "app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt" "app/build.gradle.kts"
  git commit -m "Wire FloatingBubbleService to on-device LocalWhisperEngine; remove cloud realtime path and OkHttp"
  ```
  (The `git rm` from Step 5 is included in the same commit.)

---

### Task 7: Long-form transcription - bounded-memory streaming sink

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/TranscriptSink.kt`
- Test: `app/src/test/java/com/whispereverywhere/TranscriptSinkTest.kt`
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` (route non-text-field transcripts through the sink; make per-segment errors non-fatal)

**Interfaces:**
- Consumes: `TranscriptionEngine.Listener.onCompleted(text)` / `onError(message)` (Task 4) and the `FloatingBubbleService` wiring (Task 6).
- Produces:
  ```kotlin
  class TranscriptSink(sessionFile: File, previewCapChars: Int = 4000) {
      val preview: StateFlow<String>
      fun append(segment: String)
      fun fullTextFile(): File
      fun close()
  }
  ```

- [ ] **Step 1: Write the failing test (REAL, COMPLETE)**

```kotlin
package com.whispereverywhere

import com.whispereverywhere.transcription.TranscriptSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TranscriptSinkTest {
    private fun tmp(): File = File.createTempFile("transcript", ".txt").apply { deleteOnExit() }

    @Test fun appends_full_text_to_file() {
        val f = tmp()
        val sink = TranscriptSink(f)
        sink.append("Hello world.")
        sink.append("Second sentence.")
        sink.close()
        val text = f.readText()
        assertTrue(text.contains("Hello world."))
        assertTrue(text.contains("Second sentence."))
    }

    @Test fun preview_is_capped_but_keeps_newest_tail() {
        val f = tmp()
        val sink = TranscriptSink(f, previewCapChars = 20)
        sink.append("aaaaaaaaaa")   // 10
        sink.append("bbbbbbbbbb")   // +10
        sink.append("cccccccccc")   // pushes past the cap
        val preview = sink.preview.value
        assertTrue("preview must stay capped", preview.length <= 20)
        assertTrue("preview keeps the newest text", preview.contains("cccccccccc"))
        sink.close()
    }

    @Test fun blank_segments_are_ignored() {
        val f = tmp()
        val sink = TranscriptSink(f)
        sink.append("   ")
        sink.append("")
        sink.close()
        assertEquals("", f.readText().trim())
    }
}
```

- [ ] **Step 2: Run the test -> expected FAIL**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.whispereverywhere.TranscriptSinkTest"`
Expected: FAIL with `Unresolved reference: TranscriptSink`.

- [ ] **Step 3: Implement TranscriptSink (REAL, COMPLETE)**

```kotlin
package com.whispereverywhere.transcription

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Bounded-memory transcript accumulator for indefinite-length sessions.
 * Every finalized segment is appended to [sessionFile]; only the last [previewCapChars]
 * characters are retained in memory for the live preview, so a multi-hour session never
 * grows RAM. The full transcript is always the file on disk.
 */
class TranscriptSink(
    private val sessionFile: File,
    private val previewCapChars: Int = 4000,
) {
    private val _preview = MutableStateFlow("")
    val preview: StateFlow<String> = _preview

    private val writer: BufferedWriter = BufferedWriter(FileWriter(sessionFile, /* append = */ true))
    private val tail = StringBuilder()

    @Synchronized
    fun append(segment: String) {
        val s = segment.trim()
        if (s.isEmpty()) return
        writer.write(s)
        writer.write(" ")
        writer.flush()
        tail.append(s).append(' ')
        if (tail.length > previewCapChars) {
            tail.delete(0, tail.length - previewCapChars)
        }
        _preview.value = tail.toString()
    }

    fun fullTextFile(): File = sessionFile

    @Synchronized
    fun close() {
        try {
            writer.flush()
            writer.close()
        } catch (_: Exception) {
        }
    }
}
```

- [ ] **Step 4: Run the test -> expected PASS**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.whispereverywhere.TranscriptSinkTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/transcription/TranscriptSink.kt app/src/test/java/com/whispereverywhere/TranscriptSinkTest.kt
git commit -m "Task7: bounded-memory TranscriptSink for indefinite-length sessions"
```

- [ ] **Step 6: Route non-text-field transcripts through the sink + make segment errors non-fatal**

In `FloatingBubbleService` (read it first to match field/method names), make four changes:

(a) Add a field and create the sink per session in `startRecording()` for non-`TEXT_FIELD` context, observing its capped preview into the preview view:

```kotlin
private var transcriptSink: com.whispereverywhere.transcription.TranscriptSink? = null

// in startRecording(), once currentContext is known and != BubbleContext.TEXT_FIELD:
val sessionFile = java.io.File(filesDir, "transcript_session.txt").apply { if (exists()) delete() }  // filesDir, NOT cacheDir: not OS-evictable mid-session
val sink = com.whispereverywhere.transcription.TranscriptSink(sessionFile)
transcriptSink = sink
serviceScope.launch(Dispatchers.Main) {
    sink.preview.collectLatest { text ->
        transcriptionEditText.setText(text)
        transcriptionEditText.setSelection(text.length)
    }
}
```

(b) In the `onCompleted` callback, for non-`TEXT_FIELD` context, replace the growing
`transcriptionEditText.append(...)` / `sessionTranscription.append(...)` accumulation with:

```kotlin
transcriptSink?.append(trimmed)   // bounded memory; the preview updates via the StateFlow above
```

(Text-field context is unchanged — it injects `trimmed` immediately and accumulates nothing.)

(c) Make a per-segment transcription failure non-fatal so long sessions survive glitches. **This `onError` supersedes the version authored in Task 6 Step 3** (which always tore down); use this one:

```kotlin
override fun onError(message: String) {
    if (currentState == BubbleState.RECORDING) {
        // mid-session segment failure -> log and keep recording; do NOT tear down
        android.util.Log.w("FloatingBubble", "Transcription segment failed (continuing): $message")
        return
    }
    // connect-time / fatal (e.g. no model installed)
    serviceScope.launch(Dispatchers.Main) {
        updateBubbleState(BubbleState.ERROR)
        teardownRealtime()
    }
}
```

(d) In `stopRecording()`, use the sink's file as the final result (bounded), then close it. **This supersedes Task 6's `stopRecording()` final-copy**: keep the existing finalize wait and teardown (`delay(1500)` then `teardownRealtime()`), but replace the non-`TEXT_FIELD` tail that copied `transcriptionEditText`/`sessionTranscription` to the clipboard (~lines 661–700 of the real file) with:

```kotlin
transcriptSink?.let { sink ->
    sink.close()
    val full = sink.fullTextFile().readText().trim()
    if (full.isNotEmpty()) {
        val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clip.setPrimaryClip(android.content.ClipData.newPlainText("Transcript", full))
    }
}
transcriptSink = null
```

- [ ] **Step 7: Verify build + long-run manual check**

Run: `.\gradlew.bat assembleDebug`
Expected: `BUILD SUCCESSFUL`. Manual: start a media/dictation session and let it run several minutes of
continuous audio (e.g. a long video); confirm the preview stays capped (does not grow), the session file
keeps accumulating, memory stays flat in Android Studio Profiler, and a deliberately-forced segment error
(briefly rename the model file mid-session) is logged without stopping recording.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt
git commit -m "Task7: stream long-form transcripts to a bounded sink; segment errors non-fatal"
```

