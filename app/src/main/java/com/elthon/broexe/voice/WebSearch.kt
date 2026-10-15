package com.elthon.broexe.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val domain: String,
)

/**
 * The one and only network tool: DuckDuckGo's HTML endpoint. No API key, no
 * server-side anything, bounded timeouts, bounded result count, cancellable
 * (coroutine cancellation aborts the call).
 */
class WebSearch(
    private val client: OkHttpClient = defaultClient(),
) {

    suspend fun search(query: String, limit: Int = MAX_RESULTS): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val body = ("q=" + java.net.URLEncoder.encode(query, "UTF-8") + "&kl=us-en")
                .toRequestBody(FORM)
            val request = Request.Builder()
                .url(ENDPOINT)
                .header("User-Agent", USER_AGENT)
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val html = response.body?.string().orEmpty()
                DuckDuckGoParser.parse(html).take(limit)
            }
        }

    companion object {
        const val ENDPOINT = "https://html.duckduckgo.com/html/"
        const val MAX_RESULTS = 5
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
        private val FORM = "application/x-www-form-urlencoded".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Pure HTML → results parser, split out so it can be unit-tested against a fixed
 * DuckDuckGo sample without any network.
 */
object DuckDuckGoParser {

    private val linkRe = Regex(
        "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val snippetRe = Regex(
        "<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>",
        RegexOption.DOT_MATCHES_ALL,
    )

    fun parse(html: String): List<SearchResult> {
        val snippets = snippetRe.findAll(html).map { stripTags(it.groupValues[1]) }.toList()
        return linkRe.findAll(html).mapIndexed { index, match ->
            val url = decodeUrl(match.groupValues[1])
            val title = stripTags(match.groupValues[2])
            SearchResult(
                title = title,
                url = url,
                snippet = snippets.getOrElse(index) { "" },
                domain = hostOf(url),
            )
        }.filter { it.title.isNotBlank() && it.url.isNotBlank() }.toList()
    }

    /** DDG wraps results in a redirect; the real target is the `uddg` param. */
    fun decodeUrl(href: String): String {
        val raw = when {
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> "https://duckduckgo.com$href"
            else -> href
        }
        return runCatching {
            val query = URI(raw).rawQuery ?: return raw
            for (pair in query.split('&')) {
                val idx = pair.indexOf('=')
                if (idx <= 0) continue
                if (pair.substring(0, idx) == "uddg") {
                    return URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                }
            }
            raw
        }.getOrDefault(raw)
    }

    fun hostOf(url: String): String =
        runCatching { URI(url).host?.removePrefix("www.").orEmpty() }.getOrDefault("")

    fun stripTags(input: String): String =
        input.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
