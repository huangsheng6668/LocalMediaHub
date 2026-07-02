import java.util.Properties
import java.io.FileInputStream

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
                storeFile = File("${System.getProperty("user.home")}/.android/debug.keystore")
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
    }

    buildTypes {
        release {
            // Enable R8 code shrinking + resource shrinking for release builds.
            // ProGuard/R8 rules are maintained in proguard-rules.pro; see there
            // for the reflection/JNI keep rules required by Retrofit, Gson,
            // Compose, DataStore and the native image decoder.
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
    }
    // Native image decoder CMake build — uses pre-built static libs from cpp/libs/
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

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
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.0")

    // Hilt dependency injection
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-android-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Network
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Video player
    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-ui:1.2.0")
    // FFmpeg extension: media3-decoder-ffmpeg is not published to Maven.
    // libffmpeg.so is pre-built in jniLibs/arm64-v8a/ for future FFmpeg JNI bridge.

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Serialization — version pinned to 2.8.9 to match converter-gson:2.9.0
    // (Gson 2.10+ changed internal APIs: "Class cannot be cast to ParameterizedType").
    implementation("com.google.code.gson:gson:2.8.9")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.robolectric:robolectric:4.13")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
