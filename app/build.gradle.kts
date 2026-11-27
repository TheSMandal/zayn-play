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
        // VERSION SCHEME (v0.2.10): review builds keep sequential
        // versionCodes (16, 17, 18, 19, ...). The FIRST real release
        // build MUST use versionCode >= 1000, so it can update-install
        // over every review build without an uninstall (Android refuses
        // a downgrade in versionCode even with the same signature).
        versionCode = 1003
        versionName = "1.0.3"
    }

    // FIXED SIGNING (v0.2.10): every build — debug AND release — must be
    // signed with ONE fixed keystore, so each APK update-installs over
    // the previous one. Before v0.2.10 every CI run generated a fresh
    // debug keystore (v0.2.7/0.2.8/0.2.9 carried three different debug
    // certificates), so Android refused update-install and the user had
    // to uninstall first every time. The fixed keystore is the release
    // keystore, so review builds and the future release share one
    // signature. CI decodes the KEYSTORE_BASE64 secret to keystore.jks
    // and passes -PfixedKeystoreFile=... -PfixedKeystorePassword=...
    // -PfixedKeyAlias=... -PfixedKeyPassword=... -PrequireFixedSigning=true.
    // With requireFixedSigning=true the build FAILS rather than silently
    // falling back to a fresh debug keystore. Without it (local dev),
    // a missing keystore falls back to the default debug signing.
    val requireFixedSigning =
        providers.gradleProperty("requireFixedSigning").orNull == "true"
    val fixedKeystorePath =
        providers.gradleProperty("fixedKeystoreFile").orNull
            ?: System.getenv("KEYSTORE_FILE")
    val fixedKeystorePassword =
        providers.gradleProperty("fixedKeystorePassword").orNull
            ?: System.getenv("KEYSTORE_PASSWORD")
    val fixedKeyAlias =
        providers.gradleProperty("fixedKeyAlias").orNull
            ?: System.getenv("KEY_ALIAS")
    val fixedKeyPassword =
        providers.gradleProperty("fixedKeyPassword").orNull
            ?: System.getenv("KEY_PASSWORD")
    val hasFixedKeystore = fixedKeystorePath != null &&
        file(fixedKeystorePath).exists() &&
        fixedKeystorePassword != null && fixedKeyAlias != null &&
        fixedKeyPassword != null
    if (requireFixedSigning && !hasFixedKeystore) {
        throw GradleException(
            "requireFixedSigning=true but no usable fixed keystore found. " +
            "Pass -PfixedKeystoreFile=<path> -PfixedKeystorePassword=... " +
            "-PfixedKeyAlias=... -PfixedKeyPassword=... (CI decodes the " +
            "KEYSTORE_BASE64 secret to keystore.jks first). Refusing to " +
            "fall back to a fresh debug keystore: that would break " +
            "update-installs."
        )
    }
    signingConfigs {
        if (hasFixedKeystore) {
            create("fixed") {
                storeFile = file(fixedKeystorePath!!)
                storePassword = fixedKeystorePassword
                keyAlias = fixedKeyAlias
                keyPassword = fixedKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasFixedKeystore) signingConfig = signingConfigs.getByName("fixed")
        }
        debug {
            // The fixed keystore (above) signs debug builds too, so
            // every review APK updates over the previous one.
            isDebuggable = true
            if (hasFixedKeystore) signingConfig = signingConfigs.getByName("fixed")
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

// (V1.0.4) Exactly ONE coroutines-core on every classpath: the patched jar
// in dependencies below. Any Maven coroutines-core — pulled transitively by
// coroutines-android, ktor-http, or anything else — would put a second
// BuildersKt next to it (the duplicate-class failure the multifile-part
// shim trial hit; VERIFY-1.0.4 §1). files() deps ignore excludes, so the
// patched jar survives this.
configurations.all {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
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
    // (V1.0.4) Patched coroutines-core, NOT the Maven artifact: byte-identical
    // to kotlinx-coroutines-core-jvm 1.8.1 (Maven SHA-1
    // 00bb0e192bd7c2b6b8217440d36e9758e377e450) except BuildersKt.class gains
    // ONE method, runBlockingK, delegating to BuildersKt__BuildersKt.runBlocking
    // — the JVM name coroutines 1.11.0 gives runBlocking, which the live Phisher
    // plugins call (INVESTIGATION-3-runblocking.md). The patch was applied once
    // offline with ASM (PatchBuildersKt.java, kept with the trial notes in
    // verify-1.0.4/); re-run it if the core version ever changes. REMOVE the
    // patched jar and restore the Maven core line if coroutines is ever upgraded
    // to 1.11.0+, which ships runBlockingK natively.
    // The global exclude below is load-bearing: ANY Maven core on the classpath
    // (transitively pulled by coroutines-android, ktor-http, or anything else)
    // would put a second BuildersKt next to this jar — the duplicate-class
    // failure the multifile-part shim trial hit (VERIFY-1.0.4 §1). files() deps
    // are not affected by excludes, so the patched jar survives it.
    implementation(files("libs/kotlinx-coroutines-core-1.8.1-patched.jar"))
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
    // fuzzywuzzy, juniversalchardet, cryptography (call sites rewritten
    // on javax.crypto).
    // v0.2.10 — missing host classes from the full plugin sweep
    // (SWEEP-0.2.10.md + the all-repo sweep): these ARE referenced by
    // live plugin code and were absent from the host dex.
    // Gson: XDMovies news one up in its own <clinit> (load-breaking)
    // plus 9 more plugins' content paths. Pure Java, no Kotlin
    // metadata risk. Version 2.10.1 covers every referenced member
    // (Gson/<init>/fromJson/toJson, JsonObject/JsonElement/JsonArray,
    // JsonParser.parseString, TypeToken subclasses).
    implementation("com.google.code.gson:gson:2.10.1")
    // ksoup (jsoup fork, com.fleeksoft.ksoup): 6 plugins' content
    // paths. Version 0.2.5 — NOT 0.2.6: 0.2.5 carries Kotlin metadata
    // mv 2.0.0 (readable by the frozen kotlinc 1.9.24), 0.2.6 is
    // mv 2.3.0 and is rejected (same blocker as Investigation A).
    // Its transitive kotlin-stdlib 2.2.0 is EXCLUDED and forced back
    // to 1.9.24 below — otherwise Gradle would upgrade the host
    // stdlib and kotlinc 1.9.24 would reject its own stdlib.
    implementation("com.fleeksoft.ksoup:ksoup-jvm:0.2.5") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    // NewPipe Extractor (YoutubeProvider's whole content path, 18
    // classes). Version v0.26.1 via JitPack — the newest version whose
    // referenced methods/fields are all present (v0.27.0 removed
    // Description.getContent()). The plugin never calls NewPipe.init,
    // so the host inits it once at startup (EngineApp) over OkHttp.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.1")
    // Material (1.12.0 — the last release for compileSdk 34; 1.13.0
    // needs 35). The full sweep proves 209 plugins' dialog/settings
    // classes EXTEND BottomSheetDialogFragment (CfWebViewDialog,
    // Turnstile dialogs, provider Settings fragments) and 15+ plugins
    // instantiate Material widgets (TextInputEditText, MaterialButton,
    // MaterialSwitch, FloatingActionButton, MaterialAlertDialogBuilder)
    // in settings/dialog paths. Without the classes those screens
    // fail to load; with them, they can open. No Cloudflare solver is
    // added — a challenge dialog still cannot solve a challenge.
    implementation("com.google.android.material:material:1.12.0")
    // Brotli decoder: bigbull CizgiSiteProvider.fetchHtml news one up
    // to decompress a Brotli response body. Pure Java, tiny.
    implementation("org.brotli:dec:0.1.2")
}

// Force the frozen stdlib: ksoup's transitive deps would otherwise
// pull kotlin-stdlib 2.2.0, whose metadata kotlinc 1.9.24 rejects.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" &&
            requested.name == "kotlin-stdlib"
        ) {
            useVersion("1.9.24")
            because("Frozen toolchain: kotlinc 1.9.24 cannot read newer stdlib metadata")
        }
    }
}
