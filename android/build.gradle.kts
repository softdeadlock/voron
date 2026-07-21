plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "messenger.android"
    // Crypto (ChaCha20-Poly1305, Ed25519 via Conscrypt) verified working on a
    // real device (Pixel, API 36) — see project notes. Re-verify before
    // lowering minSdk below API 33.
    compileSdk = 36

    defaultConfig {
        applicationId = "messenger.android"
        minSdk = 33
        targetSdk = 36
        versionCode = 3
        versionName = "0.4"
        // SIZE: stream-webrtc-android ships prebuilt native libs for all four ABIs unconditionally
        // (~41MB combined in an unfiltered APK — the single biggest chunk of app size after dex).
        // x86/x86_64 only ever matter on an emulator; every real phone is arm64-v8a (or, rarely,
        // the older armeabi-v7a) — dropping the emulator-only pair here cuts that ~41MB to ~16MB
        // with zero effect on any real device, including the one this app is actually tested on.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isDebuggable = false
            // SIZE: nothing shrinks the dex without this — every class from every dependency
            // (including whatever WebRTC/Compose/Ktor code this app never calls) ships whole.
            // Debug builds stay unminified/unshrunk on purpose: fast iteration, readable stack
            // traces, and a mismatch here could never affect anything a real user installs.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes.add("META-INF/INDEX.LIST")
        resources.excludes.add("META-INF/*.kotlin_module")
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":common"))

    // OkHttp is the well-supported Ktor client engine on Android.
    implementation(libs.ktor.client.okhttp)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.work.runtime.ktx)
    // androidx.biometric:1.1.0 (2021) transitively pulls androidx.fragment:1.2.5, and nothing
    // else in the graph asks for anything newer — plain Gradle conflict resolution then leaves
    // that ancient FragmentActivity in the APK. Its startActivityForResult unconditionally
    // rejects any requestCode >= 65536 (see FragmentActivity.checkForValidRequestCode), while
    // androidx.activity's modern ActivityResultRegistry (which MainActivity's
    // rememberLauncherForActivityResult calls — the QR scanner, photo/document pickers all use
    // it) always generates codes >= 65536. Every picker launch threw
    // "IllegalArgumentException: Can only use lower 16 bits for requestCode", unconditionally,
    // on every attempt — this explicit pin forces a fragment version actually current with
    // activity:1.13.0, which no longer has that restriction for registry-issued codes.
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation(libs.stream.webrtc.android)
    // QR add-contact: zxing-core renders the QR, the embedded scanner works without any
    // Google services (no ML Kit) — a hard requirement for de-Googled ROMs.
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)
    // Link previews: parses the sender's own fetched HTML into a title/og:image URL and a
    // reader-mode plain-text extraction — pure Java, no native code, doesn't touch APK size the
    // way a native dependency would.
    implementation(libs.jsoup)
    // Excludes the plain JVM `tink` artifact: it duplicates classes already provided by
    // `tink-android` (pulled in transitively via androidx.security.crypto), and this app never
    // uses UnifiedPush's WebPush key-set encryption (registerPush only ever needs the endpoint
    // URL) so dropping it loses nothing.
    implementation(libs.unifiedpush.connector) {
        exclude(group = "com.google.crypto.tink", module = "tink")
    }
    debugImplementation(libs.androidx.compose.ui.tooling)
}
