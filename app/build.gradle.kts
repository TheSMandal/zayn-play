plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Phase B: @Serializable data classes in the vendored engine.
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.netlify.zaynplay"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.netlify.zaynplay"
        minSdk = 24
        targetSdk = 34
        // UI-review build only — never to be released as-is.
        versionCode = 18
        versionName = "0.2.9"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // UI-only build: no signing config here. The CI/release signing
            // is arranged by the parent when a real build is made.
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Phase B — engine core (engine-design.md §2.3, versions PROVEN against
    // the frozen Kotlin 1.9.24 toolchain with a local kotlinc 1.9.24 probe:
    // the design's 2.3-metadata versions (ktor 3.5.0, serialization-json
    // 1.11.0, coroutines 1.11.0, nicehttp 0.4.18, ksoup 0.2.6,
    // cryptography 0.6.0) are REJECTED by kotlinc 1.9.24 — "can read
    // versions up to 2.0.0" — so the 1.9-readable versions below are used
    // instead. Toolchain stays frozen: AGP 8.5.2 / Kotlin 1.9.24 / SDK 34.
    // (Round 2: serialization-json 1.7.3 ALSO rejected — its runtime
    // requires Kotlin 2.0.0-RC1 (CI 45836444256 + local probe); 1.6.3
    // is the last 1.9-compatible release. Same for datetime 0.6.2.)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    // kotlinx-datetime 0.6.2: MainAPI date APIs. 0.5.0 has NO format
    // package (no DateTimeComponents.Format/byUnicodePattern); 0.8.0
    // (clone) needs Kotlin 2.x. 0.6.2 probe-compiles under kotlinc
    // 1.9.24 with the exact MainAPI usages, no version error.
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
    // PINNED: later jackson-module-kotlin crashes on minSdk < 26.
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    implementation("org.jsoup:jsoup:1.22.1")
    // ktor-http only (MainAPI URL types). No ktor-client: the vendored
    // NiceHttp source uses OkHttp directly, not ktor-client.
    implementation("io.ktor:ktor-http:2.3.12")
    // PINNED: rhino 1.9.0+ needs minSdk 26.
    implementation("org.mozilla:rhino:1.8.1")
    // Media3 player stack (Phase C player; no media3-ui/cast/session).
    // PINNED 1.4.1: the last release whose AAR metadata allows
    // compileSdk 34 (verified: aar-metadata.properties minCompileSdk=34
    // for all five modules). 1.5.1 already needs 35, and the toolchain is
    // FROZEN at compileSdk 34 / AGP 8.5.2, so 1.9.3 fails
    // checkDebugAarMetadata (CI run 45835898352, 10 issues, all media3).
    // No Phase B code references androidx.media3, so the downgrade changes
    // no behaviour; the Phase C player targets 1.4.1 APIs.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    // PINNED: conscrypt-android 2.5.3 crashes everything.
    implementation("org.conscrypt:conscrypt-android:2.5.2")
    // OkHttp 4.12.0 for the vendored NiceHttp source (its 5.3.2 binary is
    // Kotlin 2.3 metadata; 4.12.0 is mv 1.8.0). org.json is also NiceHttp's.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("org.json:json:20251224")
    // PreferenceManager (vendored DataStore). Base artifact only — no
    // preference-ktx, no Compose, no Coil, no Navigation, no nextlib.
    implementation("androidx.preference:preference:1.2.1")
    // NOT added (grep-proven: zero references in the vendored code):
    // gson, fuzzywuzzy, juniversalchardet, ksoup (call sites use Jsoup),
    // cryptography (call sites rewritten on javax.crypto).
}
