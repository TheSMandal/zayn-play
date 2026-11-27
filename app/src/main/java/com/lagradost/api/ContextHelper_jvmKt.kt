package com.lagradost.api

import app.netlify.zaynplay.engine.EngineApp

/**
 * (v0.2.10) Minimal restore of upstream's multiplatform ContextHelper
 * (library module, com.lagradost.api). The file name is deliberate:
 * top-level functions in ContextHelper_jvmKt.kt compile to the class
 * `ContextHelper_jvmKt`, which is exactly what StreamCenter's
 * Cloudflare session helper calls (getContext()).
 *
 * Upstream holds the context in a WeakReference set by setContext()
 * at app start. This host never calls setContext(), so getContext()
 * returns the EngineApp application context directly — it IS the app
 * context, and it is always available after EngineApp.init. The
 * plugin uses it to build a WebView for Cloudflare solving; an
 * application context works for that, though activity-themed UI
 * would need an Activity.
 */
fun getContext(): Any? = EngineApp.context

fun setContext(context: Any?) {
    // No-op: EngineApp.context is the source of truth (see above).
}
