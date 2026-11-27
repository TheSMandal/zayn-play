package com.lagradost.cloudstream3.utils

/**
 * (v0.2.10) Minimal restore of upstream's TextUtilKt.txt(String).
 * StreamCenter and the anicompat provider call txt(String) to build
 * UiText labels for their action rows. Upstream's implementation is
 * exactly this one line. (The host's UiText.kt already defines the
 * same function, but it compiles to UiTextKt — the plugins call
 * TextUtilKt, so this file must exist.)
 */
fun txt(value: String): UiText {
    return UiText.DynamicString(value)
}
