package com.lagradost.cloudstream3.utils

/**
 * (v0.2.10) Upstream's TextUtilKt.txt(String). StreamCenter and the
 * anicompat provider call txt(String) to build UiText labels for
 * their action rows. Upstream's implementation is exactly this one
 * line. This function used to live in UiText.kt (same package); it
 * was moved here because the plugins call the class TextUtilKt, and
 * two top-level txt(String) in one package do not compile.
 *
 * File name matters: a Kotlin file TextUtil.kt compiles to the class
 * TextUtilKt — the name the plugins call. (An earlier TextUtilKt.kt
 * compiled to TextUtilKtKt, which nothing calls.)
 */
fun txt(value: String): UiText {
    return UiText.DynamicString(value)
}
