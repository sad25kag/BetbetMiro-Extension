@file:Suppress("DEPRECATION")

package com.sad25kag.noxx

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder

internal object IndonesianSubtitleResolver {
    private const val INDONESIAN_LABEL = "Indonesian"

    private val browserHeaders =
        mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "User-Agent" to USER_AGENT,
        )

    suspend fun collect(
        detailUrl: String,
        html: String,
        document: Document,
        serverUrls: List<String>,
        mainUrl: String,
    ): List<SubtitleFile> {
        val subtitles = mutableListOf<SubtitleFile>()
        val seen = linkedSetOf<String>()

        fun add(items: List<SubtitleFile>) {
            items.forEach { subtitle ->
                if (subtitle.url.isNotBlank() && seen.add(subtitle.url)) {
                    subtitles += subtitle
                }
            }
        }

        add(extractFromUrls(serverUrls, mainUrl))
        add(extractFromText(html, mainUrl))

        if (subtitles.isNotEmpty()) return subtitles

        val tmdbId = extractTmdbId(html, document, serverUrls)
        val vidsrcUrls = mutableListOf<String>()
        serverUrls
            .map { it.replace("&amp;", "&").trim() }
            .filter { it.contains("vidsrc", true) || it.contains("vsembed", true) }
            .forEach { vidsrcUrls += it }
        tmdbId?.let { vidsrcUrls += "https://vidsrc.xyz/embed/movie?tmdb=$it" }

        vidsrcUrls
            .distinct()
            .forEach { rawUrl ->
                val embedUrl = normalizeVidsrcUrl(rawUrl)
                val embedResponse =
                    runCatching {
                        app.get(
                            embedUrl,
                            referer = "$mainUrl/",
                            headers = browserHeaders,
                            timeout = 30L,
                        )
                    }.getOrNull() ?: return@forEach
                add(extractFromText(embedResponse.text, getBaseUrl(embedResponse.url)))
            }

        return subtitles
    }

    fun extractFromText(text: String, baseUrl: String): List<SubtitleFile> {
        val subtitles = mutableListOf<SubtitleFile>()
        val seen = linkedSetOf<String>()

        fun add(label: String, rawUrl: String?) {
            val url = rawUrl.decodeJsonUrlOrNull().toAbsoluteUrl(baseUrl) ?: return
            if (!isSubtitleUrl(url) || !isIndonesianLabel(label)) return
            if (seen.add(url)) subtitles += SubtitleFile(INDONESIAN_LABEL, url)
        }

        Jsoup.parse(text).select("track[src], source[src]").forEach { track ->
            val label =
                listOf(
                    track.attr("srclang"),
                    track.attr("label"),
                    track.attr("data-lang"),
                    track.attr("data-language"),
                    track.attr("kind"),
                ).joinToString(" ")
            add(label, track.attr("src"))
        }

        subtitleUrlRegex.findAll(text).forEach { match ->
            val rawUrl = match.value.decodeJsonUrl()
            val start = (match.range.first - 180).coerceAtLeast(0)
            val end = (match.range.last + 180).coerceAtMost(text.length - 1)
            val context = text.substring(start, end + 1).decodeJsonUrl()
            add(context, rawUrl)
        }

        return subtitles
    }

    private fun extractFromUrls(urls: List<String>, baseUrl: String): List<SubtitleFile> {
        return urls.mapNotNull { rawUrl ->
            val url = rawUrl.replace("&amp;", "&").trim()
            val subtitleUrl = url.queryValue("c1_file") ?: return@mapNotNull null
            val label = url.queryValue("c1_label") ?: ""
            val absoluteUrl = subtitleUrl.toAbsoluteUrl(baseUrl) ?: return@mapNotNull null
            if (!isSubtitleUrl(absoluteUrl) || !isIndonesianLabel(label)) return@mapNotNull null
            SubtitleFile(INDONESIAN_LABEL, absoluteUrl)
        }.distinctBy { it.url }
    }

    private fun extractTmdbId(
        html: String,
        document: Document,
        serverUrls: List<String>,
    ): String? {
        val selectors =
            listOf(
                "[data-tmdb]" to "data-tmdb",
                "[data-tmdb-id]" to "data-tmdb-id",
                "[data-tmdbid]" to "data-tmdbid",
            )
        selectors.forEach { (selector, attr) ->
            document.select(selector).forEach { element ->
                val value = element.attr(attr).trim().takeIf { it.matches(Regex("\\d+")) }
                if (!value.isNullOrBlank()) return value
            }
        }

        serverUrls.forEach { url ->
            url.queryValue("tmdb")?.takeIf { it.matches(Regex("\\d+")) }?.let { return it }
        }

        return Regex("""(?i)(?:tmdb|tmdb_id|tmdbId)[^0-9]{0,24}([0-9]{3,})""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun normalizeVidsrcUrl(url: String): String {
        val cleanUrl = url.replace("&amp;", "&").trim()
        if (!cleanUrl.contains("vidsrc", true) && !cleanUrl.contains("vsembed", true)) return cleanUrl
        if (cleanUrl.contains("autoplay=", true)) return cleanUrl
        val joiner = if (cleanUrl.contains("?")) "&" else "?"
        return "$cleanUrl${joiner}autoplay=1"
    }

    private fun String?.decodeJsonUrlOrNull(): String? {
        val value = this?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        return value
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003f", "?")
            .replace("\\u002F", "/")
    }

    private fun String.decodeJsonUrl(): String {
        return replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003f", "?")
            .replace("\\u002F", "/")
    }

    private fun String?.toAbsoluteUrl(baseUrl: String): String? {
        val value = this?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        return when {
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "${baseUrl.trimEnd('/')}$value"
            else -> "${baseUrl.trimEnd('/')}/$value"
        }
    }

    private fun String.queryValue(name: String): String? {
        val value =
            Regex("""(?:[?&]|^)${Regex.escape(name)}=([^&]+)""", RegexOption.IGNORE_CASE)
                .find(this)
                ?.groupValues
                ?.getOrNull(1)
                ?: return null
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    private fun isIndonesianLabel(label: String): Boolean {
        val normalized = label.lowercase()
        return normalized == "id" ||
            normalized == "ind" ||
            normalized.contains("indonesian") ||
            normalized.contains("indonesia") ||
            normalized.contains("bahasa") ||
            normalized.contains("srclang=\\\"id\\\"") ||
            normalized.contains("srclang='id'") ||
            normalized.contains("language=\\\"id\\\"") ||
            normalized.contains("language='id'") ||
            normalized.contains("lang=\\\"id\\\"") ||
            normalized.contains("lang='id'")
    }

    private fun isSubtitleUrl(url: String): Boolean {
        val cleanUrl = url.substringBefore("#").substringBefore("?")
        return cleanUrl.endsWith(".vtt", true) || cleanUrl.endsWith(".srt", true)
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

    private val subtitleUrlRegex =
        Regex(
            """(?i)(?:https?:\\?/\\?/[^'"\\\s<>]+?|//[^'"\\\s<>]+?|/[^'"\\\s<>]+?)(?:\.vtt|\.srt)(?:\?[^'"\\\s<>]*)?""",
        )
}
