package com.lagradost.cloudstream3.utils

import android.graphics.BitmapFactory
import android.widget.ImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * (v0.2.10) Minimal restore of upstream's ImageLoader.loadImage.
 *
 * StreamCenter's settings fragments call loadImage(imageView, url,
 * headers, callback) to show provider icons. Upstream's implementation
 * is Coil3-based; this host has no Coil, so this is a small real
 * implementation instead: OkHttp download + BitmapFactory decode,
 * set on the ImageView on the main thread. The callback (an error /
 * completion handler in the plugin's code) is never invoked — the
 * caller's contract does not depend on it, and invoking it with a
 * guessed type would risk a ClassCastException. On failure the image
 * is simply left as-is.
 *
 * The loadImage$default synthetic (generated because headers and the
 * callback have defaults) matches the descriptor the plugins call.
 */
object ImageLoader {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun loadImage(
        imageView: ImageView,
        url: String?,
        headers: Map<String, String>? = null,
        callback: ((Throwable?) -> Unit)? = null,
    ) {
        if (url.isNullOrBlank()) return
        Thread {
            try {
                val reqBuilder = Request.Builder().url(url)
                headers?.forEach { (name, value) -> reqBuilder.addHeader(name, value) }
                client.newCall(reqBuilder.build()).execute().use { res ->
                    val bytes = res.body?.bytes() ?: return@Thread
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: return@Thread
                    imageView.post { imageView.setImageBitmap(bitmap) }
                }
            } catch (_: Throwable) {
                // Leave the ImageView as-is; never invoke the callback.
            }
        }.start()
    }
}
