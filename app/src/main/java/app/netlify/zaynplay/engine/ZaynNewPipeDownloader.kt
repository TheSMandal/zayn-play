package app.netlify.zaynplay.engine

import com.lagradost.cloudstream3.USER_AGENT
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.util.concurrent.TimeUnit

/**
 * (v0.2.10) OkHttp implementation of NewPipe's Downloader, for the
 * YoutubeProvider content path (NewPipeExtractor v0.26.1).
 *
 * The plugin NEVER calls NewPipe.init (zero refs in its dex), and
 * upstream's Extractor.<init> does
 * Objects.requireNonNull(NewPipe.getDownloader(), "downloader is
 * null") — so the dependency alone still NPEs at the first extractor
 * construction. EngineApp.init() calls NewPipe.init(this) once.
 *
 * Straight mapping, no tricks: method/url/headers/body from the
 * Request, Response(code, message, headers, body, finalUrl) back.
 * The extractor's own Downloader.get/post/head helpers all funnel
 * through execute(), so this one method serves the whole extractor.
 */
class ZaynNewPipeDownloader : Downloader() {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(request: Request): Response {
        val method = request.httpMethod().uppercase()
        val data = request.dataToSend()
        val builder = okhttp3.Request.Builder().url(request.url())
        var hasUserAgent = false
        for ((name, values) in request.headers()) {
            if (name.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
            for (value in values) builder.addHeader(name, value)
        }
        if (!hasUserAgent) builder.addHeader("User-Agent", USER_AGENT)
        val contentType = request.headers()["Content-Type"]?.firstOrNull()
            ?: request.headers()["content-type"]?.firstOrNull()
        val body = when {
            data != null -> data.toRequestBody(contentType?.toMediaTypeOrNull())
            method == "POST" || method == "PUT" || method == "PATCH" ->
                ByteArray(0).toRequestBody(null)
            else -> null
        }
        builder.method(method, body)
        client.newCall(builder.build()).execute().use { res ->
            val responseBody = res.body?.string() ?: ""
            return Response(
                res.code,
                res.message,
                res.headers.toMultimap(),
                responseBody,
                res.request.url.toString()
            )
        }
    }
}
