# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.whispereverywhere.**$$serializer { *; }
-keepclassmembers class com.whispereverywhere.** {
    *** Companion;
}
-keepclasseswithmembers class com.whispereverywhere.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- OkHttp 5.x -------------------------------------------------------------
# OkHttp references optional Conscrypt/BouncyCastle/OpenJSSE providers reflectively and ships
# Animal Sniffer + JSR-305 annotations that R8 warns about. These are the upstream-recommended
# rules; without them the RELEASE build fails or strips TLS provider lookup, which debug builds
# never reveal (this project has a history of release-only failures — see b19233c).
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.*
-keepclasseswithmembers class * {
    @okhttp3.* <methods>;
}

# Keep accessibility service
-keep class com.whispereverywhere.service.WhisperAccessibilityService { *; }

# Keep boot receiver
-keep class com.whispereverywhere.receiver.BootReceiver { *; }

# Keep notification listener
-keep class com.whispereverywhere.service.MediaNotificationListener { *; }

# Suppress warnings for errorprone annotations (used by Tink/Guava)
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# Tink / androidx.security EncryptedSharedPreferences references these compile-only
# annotations, which are absent from the Android classpath. They are not needed at
# runtime, so suppress the R8 missing-class errors (per R8's own missing_rules.txt).
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy

# --- Native whisper.cpp JNI bridge (Task 1) ---
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.whispereverywhere.whisper.WhisperNative { *; }

# --- sherpa-onnx (Track F read-aloud) ---
# The native side reflectively reads the config classes' fields and calls the specialized
# Function1 bridge on callbacks. R8 renaming them aborts OfflineTts.newFromFile (SIGABRT
# proven on-device, release-only, 2026-07-18). Keep the whole API surface.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class * implements kotlin.jvm.functions.Function1 {
    public java.lang.Integer invoke(float[]);
}

# --- Release log hygiene ---
# Strip ALL android.util.Log calls from release builds (incl. the WE-DIAG operational logs) —
# production posture for Play. Diagnosis happens on debug builds, where nothing is stripped.
# Native (__android_log_print) logging in whisper_jni is unaffected by R8 and stays available.
# (Transcript CONTENT is never logged at any level — removed at the call sites.)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
