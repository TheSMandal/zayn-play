package app.netlify.zaynplay.engine

import com.lagradost.cloudstream3.app
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase C subtitles (engine-design.md §1.7, E17–E18).
 *
 * Verified against the reference clone (2026-11-26):
 * - OpenSubtitles search needs the provider's consumer Api-Key (a
 *   credential registered to upstream's app — NOT copied into this repo)
 *   and its download path needs a user login token. No key is available,
 *   so OpenSubtitles answers a plain auth error, never a crash.
 * - SubDL requiresLogin = true (email + password login to obtain an API
 *   key). No login form exists in this phase, so SubDL answers the same
 *   plain auth error.
 * - Addic7ed requiresLogin = false and needs no key: its search + load
 *   are implemented for real below, ported from
 *   syncproviders/providers/Addic7ed.kt (same hosts, same query shapes,
 *   same language table — data, not a secret).
 *
 * E18 returns the subtitle file text (SRT/VTT). Addic7ed download links
 * point at the file itself; the GET carries the referer header the site
 * requires.
 */
class ZaynSubtitles {

    companion object {
        const val HOST = "https://www.addic7ed.com"

        // Exact language table from Addic7ed.kt (IETF tag -> site id, name).
        val LANG_TABLE: Map<String, Pair<String, String>> = mapOf(
            "ar" to ("38" to "Arabic"),
            "az" to ("48" to "Azerbaijani"),
            "bg" to ("35" to "Bulgarian"),
            "bn" to ("47" to "Bengali"),
            "bs" to ("44" to "Bosnian"),
            "ca" to ("12" to "Català"),
            "cs" to ("14" to "Czech"),
            "cy" to ("65" to "Welsh"),
            "da" to ("30" to "Danish"),
            "de" to ("11" to "German"),
            "el" to ("27" to "Greek"),
            "en" to ("1" to "English"),
            "es-419" to ("6" to "Spanish (Latin America)"),
            "es-ar" to ("69" to "Spanish (Argentina)"),
            "es-es" to ("5" to "Spanish (Spain)"),
            "es" to ("4" to "Spanish"),
            "et" to ("54" to "Estonian"),
            "eu" to ("13" to "Euskera"),
            "fa" to ("43" to "Persian"),
            "fi" to ("28" to "Finnish"),
            "fr-ca" to ("53" to "French (Canadian)"),
            "fr" to ("8" to "French"),
            "gl" to ("15" to "Galego"),
            "he" to ("23" to "Hebrew"),
            "hi" to ("55" to "Hindi"),
            "hr" to ("31" to "Croatian"),
            "hu" to ("20" to "Hungarian"),
            "hy" to ("50" to "Armenian"),
            "id" to ("37" to "Indonesian"),
            "is" to ("56" to "Icelandic"),
            "it" to ("7" to "Italian"),
            "ja" to ("32" to "Japanese"),
            "kn" to ("66" to "Kannada"),
            "ko" to ("42" to "Korean"),
            "lt" to ("58" to "Lithuanian"),
            "lv" to ("57" to "Latvian"),
            "mk" to ("49" to "Macedonian"),
            "ml" to ("67" to "Malayalam"),
            "mr" to ("62" to "Marathi"),
            "ms" to ("40" to "Malay"),
            "nl" to ("17" to "Dutch"),
            "no" to ("29" to "Norwegian"),
            "pl" to ("21" to "Polish"),
            "pt-br" to ("10" to "Portuguese (Brazilian)"),
            "pt" to ("9" to "Portuguese"),
            "ro" to ("26" to "Romanian"),
            "ru" to ("19" to "Russian"),
            "si" to ("60" to "Sinhala"),
            "sk" to ("25" to "Slovak"),
            "sl" to ("22" to "Slovenian"),
            "sq" to ("52" to "Albanian"),
            "sr-latn" to ("36" to "Serbian (Latin)"),
            "sr" to ("39" to "Serbian (Cyrillic)"),
            "sv" to ("18" to "Swedish"),
            "ta" to ("59" to "Tamil"),
            "te" to ("63" to "Telugu"),
            "th" to ("46" to "Thai"),
            "tl" to ("68" to "Tagalog"),
            "tlh" to ("61" to "Klingon"),
            "tr" to ("16" to "Turkish"),
            "uk" to ("51" to "Ukrainian"),
            "vi" to ("45" to "Vietnamese"),
            "yue" to ("64" to "Cantonese"),
            "zh-hans" to ("41" to "Chinese (Simplified)"),
            "zh-hant" to ("24" to "Chinese (Traditional)"),
        )
    }

    private fun fixUrl(u: String): String = when {
        u.startsWith("/") -> HOST + u
        !u.startsWith("http") -> "$HOST/$u"
        else -> u
    }

    /** Search Addic7ed only. Returns subtitle rows for the bridge. */
    suspend fun searchAddic7ed(title: String, year: Int?, lang: String): JSONArray {
        val langTag = lang.ifBlank { "en" }
        val (langNum, langName) = LANG_TABLE[langTag] ?: ("0" to "Completed")
        val query = URLEncoder.encode(title.trim(), "UTF-8")
        val response = app.get(url = "$HOST/search.php?search=$query&Submit=Search")
        val out = JSONArray()
        val finalUrl = response.url
        var downloadPage: String? = null
        when {
            finalUrl.contains("/movie/") || finalUrl.contains("/serie/") -> {
                downloadPage = finalUrl
            }
            finalUrl.contains("/show/") -> {
                // TV series episode list — direct subtitle rows.
                val showId = finalUrl.substringAfterLast("/")
                val doc = app.get(
                    "$HOST/ajax_loadShow.php?show=$showId&season=0&langs=|$langNum|&hd=0&hi=0",
                    referer = "$HOST/",
                ).document
                for (node in doc.select("#season tbody tr")) {
                    val link = node.selectFirst("a[href~=updated\\/|original\\/]")
                        ?.attr("href")?.let(::fixUrl) ?: continue
                    val name = node.select("td:eq(2)").text() + " " + node.select("td:eq(4)").text()
                    out.put(
                        JSONObject()
                            .put("id", "addic7ed|$link")
                            .put("lang", langTag)
                            .put("source", "Addic7ed")
                            .put("downloads", JSONObject.NULL)
                            .put("url", link)
                            .put("name", name),
                    )
                }
                return out
            }
            else -> {
                val doc = response.document
                val href = if (year != null && year > 0) {
                    doc.select("table.tabel a").selectFirst("a[href~=movie\\/]:contains($year)")
                        ?.attr("href")
                } else {
                    doc.select("table.tabel a").selectFirst("a[href~=movie\\/]")?.attr("href")
                } ?: return out
                downloadPage = fixUrl(href)
            }
        }
        val page = downloadPage ?: return out
        val doc = app.get(url = page).document
        for (node in doc.select(".tabel95 .tabel95 tr:has(.language):contains($langName)")) {
            val link = node.selectFirst("a[href~=updated\\/|original\\/]")
                ?.attr("href")?.let(::fixUrl) ?: continue
            val titleText = doc.selectFirst("span.titulo")?.text()
                ?.substringBefore(" Subtitle") ?: title
            val version = node.parent()?.select(".NewsTitle")?.text()
                ?.substringAfter("Version ")?.substringBefore(", Duration") ?: ""
            out.put(
                JSONObject()
                    .put("id", "addic7ed|$link")
                    .put("lang", langTag)
                    .put("source", "Addic7ed")
                    .put("downloads", JSONObject.NULL)
                    .put("url", link)
                    .put("name", "$titleText — $version"),
            )
        }
        return out
    }

    /** Load subtitle file text from an Addic7ed download link. */
    suspend fun loadAddic7ed(url: String): String? {
        if (url.isBlank()) return null
        val resp = app.get(url = url, headers = mapOf("referer" to "$HOST/"))
        if (!resp.isSuccessful) return null
        val text = resp.text
        if (text.isBlank() || text.length < 10) return null
        // A download page (HTML) instead of a subtitle file is not a file.
        if (text.trimStart().startsWith("<!DOCTYPE", ignoreCase = true) ||
            text.trimStart().startsWith("<html", ignoreCase = true)
        ) return null
        return text
    }
}
