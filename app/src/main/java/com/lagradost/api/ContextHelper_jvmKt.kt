package com.lagradost.api

import android.content.Context
import java.lang.ref.WeakReference

/**
 * (v0.2.10) Upstream's multiplatform ContextHelper (library module,
 * com.lagradost.api), restored in this file. The file name is
 * deliberate: top-level functions in ContextHelper_jvmKt.kt compile
 * to the class `ContextHelper_jvmKt`, which is exactly what
 * StreamCenter's Cloudflare session helper calls (getContext()).
 *
 * The implementation is upstream's real one: a WeakReference set by
 * setContext() — MainActivity sets it to the application context at
 * startup — and getContext() returns it. This file REPLACES the
 * host's old ContextHelper.kt (same package, same functions, same
 * behaviour); the old file was deleted because two top-level
 * getContext()/setContext() in one package are a compile error, and
 * only this file's name produces the class the plugins call.
 */
private var contextRef: WeakReference<Context>? = null

/**
 * Helper function for Android specific context. Not usable in JVM.
 * Do not use this unless absolutely necessary.
 */
fun getContext(): Any? = contextRef?.get()

fun setContext(context: Any?) {
    contextRef = (context as? Context)?.let { WeakReference(it) }
}
