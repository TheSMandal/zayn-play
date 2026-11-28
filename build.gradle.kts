// Zayn Play (v1.0.3). The approved preview (WebView) with a native splash,
// and the streaming engine behind it.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // Phase B: kotlinx-serialization plugin (1.9.24, matches the Kotlin plugin).
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
    // (v1.0.18 P16-01) KSP for the Room compiler. Version 1.9.24-1.0.20 is
    // THE KSP release built for Kotlin 1.9.24 — the frozen toolchain's
    // kotlinc accepts it (same rule as the serialization plugin above).
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}
