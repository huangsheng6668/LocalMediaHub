import java.util.Properties
import java.io.FileInputStream
import java.io.File as JFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("kotlin-kapt")
    id("com.google.dagger.hilt.android")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.juziss.localmediahub"
    compileSdk = 34

    signingConfigs {
        create("release") {
            val keyAliasVal = keystoreProperties["keyAlias"] as String?
            val keyPasswordVal = keystoreProperties["keyPassword"] as String?
            val storeFileVal = keystoreProperties["storeFile"]?.let { rootProject.file(it) }
            val storePasswordVal = keystoreProperties["storePassword"] as String?

            if (keyAliasVal != null && storeFileVal?.exists() == true) {
                keyAlias = keyAliasVal
                keyPassword = keyPasswordVal
                storeFile = storeFileVal
                storePassword = storePasswordVal
            } else {
                // WARNING: no keystore.properties found at the project root, so
                // the release build falls back to the debug signing key. This is
                // fine for local testing but MUST NOT be used for Play Store / public
                // distribution — a debug-signed APK can be resigned by anyone.
                // To sign properly, create android/keystore.properties with:
                //   storeFile=<path-to-release.keystore>
                //   storePassword=***
                //   keyAlias=***
                //   keyPassword=***
                val logger = org.gradle.api.logging.Logging.getLogger("LocalMediaHubSigning")
                logger.warn("==============================================================")
                logger.warn(" RELEASE BUILD IS USING THE DEBUG SIGNING KEY.")
                logger.warn(" No android/keystore.properties with a valid storeFile was found.")
                logger.warn(" Do NOT distribute this APK publicly.")
                logger.warn("==============================================================")
                keyAlias = "androiddebugkey"
                keyPassword = "android"
                storePassword = "android"
                storeFile = JFile("${System.getProperty("user.home")}/.android/debug.keystore")
            }
        }
    }

    defaultConfig {
        applicationId = "com.juziss.localmediahub"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += "arm64-v8a"
        }

        // Round 21 D1.1: strip translations from third-party libs down to zh + en.
        resourceConfigurations += listOf("zh", "en")
    }

    buildTypes {
        release {
            // Enable R8 code shrinking + resource shrinking for release builds.
            // ProGuard/R8 rules are maintained in proguard-rules.pro; see there
            // for the reflection/JNI keep rules required by Gson, Compose,
            // DataStore and the native image decoder. (Retrofit was removed
            // in Round 19 C3 — its keep rules are no longer needed.)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        // AGP 8.x disables BuildConfig generation by default; the native
        // loader needs BuildConfig.DEBUG to fast-fail on missing .so in
        // release builds (Round 14 Task C4).
        buildConfig = true
    }
    // NOTE: The C++ CMake `externalNativeBuild` block used to live here. It has
    // been removed in Task 0 of the Round 11 native Rust rewrite — the C++
    // source under `src/main/cpp/` is no longer built by Gradle; it is
    // scheduled for deletion in Task 3 once the Rust JPEG/WebP decoder is
    // feature-complete. The replacement Rust build is wired up via the
    // `buildRustNative` Exec task below (depends-on `preBuild`).
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Round 21 D3: OkHttp Public Suffix List resource exclusion
            excludes += "okhttp3/internal/publicsuffix/publicsuffixes.gz"
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Required so android.util.Log calls in production code don't throw
        // "Method X in android.util.Log not mocked" during JVM unit tests.
        // Round 12 Task 1: CacheCleanup uses Log.i / Log.w for telemetry.
        unitTests.isReturnDefaultValues = true
    }
}

// ----------------------------------------------------------------------------
// Rust native decoder build (Round 11 rewrite, Task 0).
//
// Replaces the old C++ CMake `externalNativeBuild` block. Invokes cargo-ndk to
// cross-compile `src/main/rust/` to a shared library for arm64-v8a, dropping
// the resulting `liblocalmedia_native.so` into `src/main/jniLibs/arm64-v8a/`
// (alongside the pre-built `libffmpeg.so`). Wired into `preBuild` so the .so
// is rebuilt before the APK is packaged.
//
// The NDK location is resolved in this order:
//   1. ANDROID_NDK_HOME / ANDROID_NDK_ROOT already in the build environment
//   2. `ndk.dir` in local.properties
//   3. The highest-versioned `<sdk>/ndk/<version>` directory inferred from
//      `sdk.dir` in local.properties
// ----------------------------------------------------------------------------
val rustProjectDir = file("src/main/rust")
val jniLibsDir = file("src/main/jniLibs")

fun resolveNdkRoot(): String? {
    val envHome = System.getenv("ANDROID_NDK_HOME")
    if (!envHome.isNullOrEmpty()) return envHome
    val envRoot = System.getenv("ANDROID_NDK_ROOT")
    if (!envRoot.isNullOrEmpty()) return envRoot

    val localPropsFile = rootProject.file("local.properties")
    if (!localPropsFile.exists()) return null
    val localProps = Properties()
    FileInputStream(localPropsFile).use { localProps.load(it) }

    val ndkDir = localProps.getProperty("ndk.dir")
    if (!ndkDir.isNullOrEmpty()) return ndkDir

    val sdkDir = localProps.getProperty("sdk.dir")
    if (!sdkDir.isNullOrEmpty()) {
        val ndkRoot = JFile(sdkDir, "ndk")
        if (ndkRoot.isDirectory) {
            // Only consider directories that actually contain a source.properties
            // file — the SDK manager leaves stub directories for not-yet-
            // downloaded NDK versions, which would break cargo-ndk.
            val latest = ndkRoot.listFiles()
                ?.filter { it.isDirectory && JFile(it, "source.properties").isFile }
                ?.maxByOrNull { it.name }
            if (latest != null) return latest.absolutePath
        }
    }
    return null
}

val buildRustNative = tasks.register<Exec>("buildRustNative") {
    group = "build"
    description = "Cross-compiles the Rust native decoder crate to arm64-v8a via cargo-ndk."
    workingDir = rustProjectDir

    val ndkRoot = resolveNdkRoot()
    if (ndkRoot != null) {
        environment("ANDROID_NDK_HOME", ndkRoot)
        environment("ANDROID_NDK_ROOT", ndkRoot)
    }

    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-o", jniLibsDir.absolutePath,
        "build", "--release"
    )
}

tasks.named("preBuild") { dependsOn(buildRustNative) }

dependencies {

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Round 21 D2: material-icons-extended removed
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.0")

    // Hilt dependency injection
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-android-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Network — Retrofit was removed in Round 19 C3: all API calls use
    // OkHttp + Gson directly (see MediaRepository). Only OkHttp remains.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Image loading
    // Round 21 D4: Coil 2.6.0 upgrade
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Video player
    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-ui:1.2.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.2.0")
    // FFmpeg extension: media3-decoder-ffmpeg is not published to Maven.
    // libffmpeg.so is pre-built in jniLibs/arm64-v8a/ for future FFmpeg JNI bridge.

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Serialization — Gson is used directly by MediaRepository (Retrofit's
    // converter-gson was removed in Round 19 C3). Version pinned to 2.8.9
    // because Gson 2.10+ changed internal APIs that broke generic TypeToken
    // resolution under R8 minification.
    implementation("com.google.code.gson:gson:2.8.9")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
