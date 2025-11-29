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

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Keep accessibility service
-keep class com.whispereverywhere.service.WhisperAccessibilityService { *; }

# Keep boot receiver
-keep class com.whispereverywhere.receiver.BootReceiver { *; }

# Keep notification listener
-keep class com.whispereverywhere.service.MediaNotificationListener { *; }
