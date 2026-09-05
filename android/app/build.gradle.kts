import java.util.Properties
import java.io.FileInputStream
import java.io.File as JFile
import java.security.MessageDigest
import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

// Phase 7 / Phase 9 (L-8): release signing guard.
//
// A valid keystore populates the real release config below. Without one, the
// debug-key fallback is filled ONLY when -PallowDebugSigning=true is passed —
// the old unconditional fill silently armed release signing with the public
// debug key for every invocation that slipped past the (CLI-only) taskNames
// check, e.g. GUI-driven builds from Android Studio. A release build with
// neither path fails fast in the gradle.taskGraph.whenReady guard below,
// which inspects the ACTUAL task graph (CLI and GUI alike) instead of
// matching strings in gradle.startParameter.taskNames.
val allowDebugSigning = (project.findProperty("allowDebugSigning") as String?) == "true"
val releaseKeystoreValid = run {
    val keyAliasVal = keystoreProperties["keyAlias"] as String?
    val storeFileVal = keystoreProperties["storeFile"]?.let { rootProject.file(it) }
    keyAliasVal != null && storeFileVal?.exists() == true
}

android {
    namespace = "com.juziss.localmediahub"
    // compileSdk bumped 34 → 36 in Round 24 Task 8: Coil 3.5.0's transitive
    // dependencies (androidx.core 1.16.0, androidx.compose 1.11.x, and the
    // coil3 android artifacts themselves) are built against SDK 36 and
    // require consumers to compile against 36+. targetSdk stays at 34 —
    // runtime behavior is unchanged, only the compile-time API surface widens.
    compileSdk = 36

    signingConfigs {
        create("release") {
            // Explicit opt-in wins over a structurally-valid keystore.properties:
            // the file only proves storeFile/keyAlias exist, not that its
            // passwords unlock the store (a placeholder recovery template is
            // "valid" here) — without this ordering the debug fallback would be
            // unreachable exactly when it is needed.
            if (allowDebugSigning) {
                // Explicit local-testing opt-in only (Chain-I: a debug-signed
                // APK can be resigned by anyone, enabling supply-chain
                // attacks — hence the loud warning).
                val logger = org.gradle.api.logging.Logging.getLogger("LocalMediaHubSigning")
                logger.warn("==============================================================")
                logger.warn(" RELEASE BUILD IS USING THE DEBUG SIGNING KEY (explicitly opted in).")
                logger.warn(" Do NOT distribute this APK publicly.")
                logger.warn("==============================================================")
                keyAlias = "androiddebugkey"
                keyPassword = "android"
                storePassword = "android"
                storeFile = JFile("${System.getProperty("user.home")}/.android/debug.keystore")
            } else if (releaseKeystoreValid) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String?
                storeFile = keystoreProperties["storeFile"]?.let { rootProject.file(it) }
                storePassword = keystoreProperties["storePassword"] as String?
            }
            // else: deliberately left unpopulated. Any Release-variant task in
            // the graph fails fast in the whenReady guard below BEFORE a task
            // executes; non-release invocations never read this signing
            // config (the debug build type uses the debug signing config).
        }
    }

    defaultConfig {
        applicationId = "com.juziss.localmediahub"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.3.1"

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
    // JVM target bumped 1.8 → 11 in Round 24 Task 8: Coil 3.5.0 and several
    // androidx transitive deps are compiled with JVM 11 bytecode. Their inline
    // functions (crossfade, pxOrElse, limitedParallelism, etc.) cannot be
    // inlined into JVM 1.8 bytecode, producing "Cannot inline bytecode built
    // with JVM target 11" compile errors.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
        // Coil 3.5.0's POM transitively pulls kotlin-stdlib 2.4.0 even though
        // Coil itself is compiled with Kotlin 2.2.0. The Kotlin 2.2.0 compiler
        // cannot read 2.4.0 metadata, producing "Incompatible classes were
        // found in dependencies" and breaking symbol resolution. This flag
        // suppresses the metadata version check so the compiler reads the
        // higher-version metadata — safe because stdlib 2.4.0 is ABI-compatible
        // with 2.2.0 for the APIs this project uses.
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
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
    // composeOptions block removed in Round 24 Task 8: with Kotlin 2.0+ the
    // Compose compiler is supplied by the `org.jetbrains.kotlin.plugin.compose`
    // Gradle plugin (applied in the plugins {} block above), so the manual
    // `kotlinCompilerExtensionVersion` is no longer needed.
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
// Phase 9 (L-8): release fail-fast on the RESOLVED task graph.
//
// The Phase 7 guard threw at configuration time when
// gradle.startParameter.taskNames contained "release" — a CLI-string match
// that (a) missed GUI-driven release builds (Android Studio populates
// taskNames differently or not at all) and (b) evaluated on every
// invocation, which forced the unconditional debug-key fallback above.
// gradle.taskGraph.whenReady fires exactly once per build AFTER the graph is
// resolved and BEFORE any task executes: it sees the tasks that will ACTUALLY
// run, CLI and GUI alike. Semantics vs Phase 7 are otherwise preserved:
// release + no keystore + no -PallowDebugSigning=true => fail with the same
// actionable message; assembleDebug / testDebugUnitTest / help / tasks all
// pass through untouched.
// ----------------------------------------------------------------------------
gradle.taskGraph.whenReady {
    val willRunReleaseTask = allTasks.any { it.name.lowercase().contains("release") }
    if (willRunReleaseTask && !releaseKeystoreValid && !allowDebugSigning) {
        throw GradleException(
            "Release build requires a valid keystore.properties at the project root.\n" +
            "To create one, copy keystore.properties.example to keystore.properties and fill in your release signing details.\n" +
            "For LOCAL TESTING ONLY, run: ./gradlew assembleRelease -PallowDebugSigning=true\n" +
            "Do NOT distribute a debug-signed APK publicly — it can be resigned by anyone."
        )
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

// ----------------------------------------------------------------------------
// Phase 2: Verify libffmpeg.so integrity against docs/sbom/libffmpeg.sha256.
// Runs at preBuild phase; fails the build if the .so was replaced without
// updating the SBOM. To update: rebuild .so per BUILD_INSTRUCTIONS.md,
// recompute sha256, update docs/sbom/libffmpeg.sha256 + docs/sbom/libffmpeg.md.
// ----------------------------------------------------------------------------
tasks.register("verifyLibffmpegSha256") {
    group = "verification"
    description = "Verify libffmpeg.so SHA256 matches docs/sbom/libffmpeg.sha256"

    val soFile = file("${projectDir}/src/main/jniLibs/arm64-v8a/libffmpeg.so")
    val hashFile = JFile(rootProject.projectDir.parentFile, "docs/sbom/libffmpeg.sha256")

    doLast {
        // Skip if .so absent (e.g. building without ffmpeg support — hardware decode only).
        if (!soFile.exists()) {
            logger.lifecycle("verifyLibffmpegSha256: libffmpeg.so absent, skipping")
            return@doLast
        }

        val actualHash = sha256(soFile)

        if (!hashFile.exists()) {
            throw GradleException(
                "docs/sbom/libffmpeg.sha256 not found at ${hashFile.absolutePath}.\n" +
                "This file is required for libffmpeg.so integrity verification.\n" +
                "If deleted accidentally, restore from git: git checkout docs/sbom/libffmpeg.sha256"
            )
        }

        val expectedLine = hashFile.readText().trim().lines().firstOrNull()
            ?: throw GradleException("docs/sbom/libffmpeg.sha256 is empty")
        val parts = expectedLine.split(Regex("\\s+"), limit = 2)
        val expectedHash = parts[0]
        val expectedPath = if (parts.size > 1) parts[1].trim() else ""

        // Phase 2 backlog: validate path field matches expected .so location.
        // Prevents silent drift if someone changes the .sha256 file path.
        val expectedSoPath = "android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so"
        if (expectedPath.isNotEmpty() && expectedPath != expectedSoPath) {
            throw GradleException(
                "docs/sbom/libffmpeg.sha256 path field mismatch!\n" +
                "  expected: $expectedSoPath\n" +
                "  actual:   $expectedPath\n" +
                "Update docs/sbom/libffmpeg.sha256 to match the actual .so location."
            )
        }

        if (!actualHash.equals(expectedHash, ignoreCase = true)) {
            throw GradleException(
                "libffmpeg.so SHA256 mismatch!\n" +
                "  actual:   $actualHash\n" +
                "  expected: $expectedHash\n" +
                "If you intentionally rebuilt libffmpeg.so, update docs/sbom/libffmpeg.sha256 " +
                "and docs/sbom/libffmpeg.md per BUILD_INSTRUCTIONS.md."
            )
        }
        logger.lifecycle("verifyLibffmpegSha256: OK ($actualHash)")
    }
}

// Helper: compute SHA256 of a file using java.security.MessageDigest.
fun sha256(file: java.io.File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { b -> "%02x".format(b) }
}

// Hook into preBuild so the check runs before every Android build.
project.tasks.named("preBuild").configure {
    dependsOn("verifyLibffmpegSha256")
}

dependencies {

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // EmojiCompat with bundled font (offline emoji support across all Android versions & fonts)
    implementation("androidx.emoji2:emoji2-bundled:1.4.0")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Task 7: responsive horizontal padding via WindowSizeClass.
    // Version managed by compose-bom (same version as material3 above) so the
    // window-size-class API surface stays ABI-aligned with material3.
    implementation("androidx.compose.material3:material3-window-size-class")
    // Round 21 D2: material-icons-extended removed
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.0")

    // Hilt dependency injection
    implementation("com.google.dagger:hilt-android:2.56.2")
    ksp("com.google.dagger:hilt-android-compiler:2.56.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Network — Retrofit was removed in Round 19 C3: all API calls use
    // OkHttp + Gson directly (see MediaRepository). Only OkHttp remains.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Image loading
    // Round 24 Task 8: Coil 2.6.0 → 3.5.0 migration + concurrency cap.
    // Core (coil-compose) + network layer (coil-network-okhttp) extracted from
    // core in v3 — required because the app loads images from server URLs.
    // Concurrent fetch+decode capped at 12 via fetcherCoroutineContext /
    // decoderCoroutineContext (Dispatchers.Default.limitedParallelism(12))
    // in LocalMediaHubApplication.newImageLoader().
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")

    // Video player
    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-ui:1.2.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.2.0")
    implementation("androidx.media3:media3-session:1.2.0")
    // FFmpeg extension: media3-decoder-ffmpeg is not published to Maven.
    // libffmpeg.so is pre-built in jniLibs/arm64-v8a/ for future FFmpeg JNI bridge.

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

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
    // mockk added in text-reader C-phase Task 5 for TextReaderViewModel unit
    // tests. 1.13.12 is the first mockk release with full Kotlin 2.x support.
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
