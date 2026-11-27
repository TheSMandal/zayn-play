package com.lagradost.cloudstream3.utils

import android.content.Context
import androidx.annotation.StringRes

/**
 * Phase B: trimmed UiText (upstream TextUtil.kt). Kept: the sealed class,
 * asString/asStringNull, txt(String) and the StringResource factory used by
 * PluginManager notifications. Dropped: TextView binding, html() (needs
 * AppContextUtils html helpers), Spanned formatting, and the
 * resource-string overloads (no app resources in the engine).
 */
sealed class UiText {
    data class DynamicString(val value: String) : UiText()
    class StringResource(@StringRes val resId: Int, vararg val args: Any) : UiText()

    fun asString(context: Context?): String {
        return when (this) {
            is DynamicString -> this.value
            is StringResource -> context?.getString(resId, *args) ?: ""
        }
    }

    fun asStringNull(context: Context?): String? {
        return when (this) {
            is DynamicString -> this.value
            is StringResource -> context?.getString(resId, *args)
        }
    }
}

