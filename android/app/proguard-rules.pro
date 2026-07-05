# ============================================================================
# LocalMediaHub ProGuard / R8 rules
# ============================================================================

# ----------------------------------------------------------------------------
# Kotlin metadata & reflection
# ----------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, SourceFile, LineNumberTable
-dontwarn kotlin.**
# Keep @Metadata so reflection-based libs (Compose, Coroutines) keep working.
-keep class kotlin.Metadata { *; }

# ----------------------------------------------------------------------------
# Data models — Gson serializes these via reflection and reads @SerializedName.
# They live under com.juziss.localmediahub.data.* (Models.kt, RoutePath.kt,
# ServerConfig.kt, etc.). Field names must be preserved verbatim.
# ----------------------------------------------------------------------------
-keep class com.juziss.localmediahub.data.** { *; }
# In case any network DTOs live elsewhere, keep anything annotated for Gson.
-keep class com.juziss.localmediahub.** { @com.google.gson.annotations.SerializedName <fields>; }
-keepclassmembers class com.juziss.localmediahub.** {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ----------------------------------------------------------------------------
# Gson itself
# ----------------------------------------------------------------------------
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class com.google.gson.reflect.TypeToken$** {
    !private !static !transient <fields>;
    !private !static !transient <methods>;
}
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
# Gson needs the no-arg constructor of POJOs it deserializes.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# R8 can strip java.lang.reflect.* classes Gson depends on for generic
# type resolution (ParameterizedType, TypeToken).  Keep them explicitly.
-keep class java.lang.reflect.Type
-keep class java.lang.reflect.ParameterizedType
-keep class java.lang.reflect.GenericArrayType
-keep class java.lang.reflect.WildcardType

# ----------------------------------------------------------------------------
# Retrofit — interface methods and annotations are resolved by reflection.
# ----------------------------------------------------------------------------
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
# Keep our Retrofit API interface verbatim — method signatures matter.
-keep class com.juziss.localmediahub.network.MediaApi { *; }

# ----------------------------------------------------------------------------
# OkHttp / Okio
# ----------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# Keep platform spec files (loaded via reflection).
-keep class okhttp3.internal.platform.** { *; }

# ----------------------------------------------------------------------------
# Coroutines
# ----------------------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# ----------------------------------------------------------------------------
# Jetpack Compose — the compiler plugin emits its own rules, but keep a safety
# net for runtime lambdas accessed via reflection.
# ----------------------------------------------------------------------------
-dontwarn androidx.compose.**

# ----------------------------------------------------------------------------
# DataStore (protobuf-free preferences variant uses reflection on key types)
# ----------------------------------------------------------------------------
-keep class androidx.datastore.** { *; }

# ----------------------------------------------------------------------------
# Coil image loader
# ----------------------------------------------------------------------------
-dontwarn coil.**

# ----------------------------------------------------------------------------
# Media3 / ExoPlayer
# ----------------------------------------------------------------------------
-dontwarn androidx.media3.**

# ----------------------------------------------------------------------------
# JNI / native image decoder
# Native methods are looked up by name from the `#[no_mangle]` symbols emitted
# by the Rust crate; the enclosing class names and the native method names
# must survive obfuscation exactly as written. The decoder classes live under
# the `native` sub-package.
# ----------------------------------------------------------------------------
# Rust JNI native methods — the broad package keep below already preserves the
# class and member names exactly as expected by the #no_mangle symbols, and the
# generic `keepclasseswithmembernames` rule covers any other native methods.
# Factory + helpers in the same sub-package are referenced by reflection from
# Coil / DI; keep the package surface stable.
-keep class com.juziss.localmediahub.native.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
