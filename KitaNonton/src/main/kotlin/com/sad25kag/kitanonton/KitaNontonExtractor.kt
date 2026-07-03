package com.kitanonton

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.kitanonton.KitaNontonUtils.absoluteUrl
import com.kitanonton.KitaNontonUtils.ajaxHeaders
import com.kitanonton.KitaNontonUtils.siteHeaders
import com.kitanonton.KitaNontonUtils.videoHeaders
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLDecoder

object KitaNontonExtractor {
    private const val TAG = "KitaNonton"
    private const val MAX_AJAX_PROBES = 24
    private const val MAX_EMBEDS = 20

    private data class AjaxProbe(val post: String, val nume: String, val type: String)

    private val iframeRegex = Regex("""(?i)<iframe[^>]+src\s*=\s*['\"]([^'\"]+)['\"]""")
    private val quotedUrlRegex = Regex("""(?i)['\"]((?:https?:)?//[^'\"<>\s]+|/[^'\"<>\s]+)['\"]""")
    private val adminAjaxRegex = Regex("""(?i)(?:https?:)?//[^'\"<>\s]+admin-ajax\.php[^'\"<>\s]*|/[^'\"<>\s]*admin-ajax\.php[^'\"<>\s]*""")

    suspend fun loadLinks(
        providerName: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.e(TAG, "loadLinks start: $data")
        val emitted = linkedSetOf<String>()
        val found = if (isLikelyEmbedOrMedia(data)) {
            resolveEmbed(providerName, data, KitaNontonUtils.MAIN_URL, emitted, subtitleCallback, callback)
        } else {
            resolveDetail(providerName, data, emitted, subtitleCallback, callback)
        }
        if (!found) Log.e(TAG, "loadLinks callback 0 link: $data")
        return found
    }

    private suspend fun resolveDetail(
        providerName: String,
        pageUrl: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = runCatching { app.get(pageUrl, headers = siteHeaders(pageUrl), referer = pageUrl) }
            .onFailure { Log.e(TAG, "detail GET failed $pageUrl: ${it.message}") }
            .getOrNull() ?: return false
        val document = response.document
        collectSubtitles(pageUrl, document, subtitleCallback)
        val ajaxEmbeds = fetchAjaxEmbeds(pageUrl, document)
        val inlineEmbeds = extractEmbeds(pageUrl, response.text)
        val embeds = (ajaxEmbeds + inlineEmbeds).mapNotNull { normalizeUrl(pageUrl, it) }.distinct().take(MAX_EMBEDS)
        Log.e(TAG, "detail=$pageUrl embeds=${embeds.size} sample=${embeds.take(4)}")
        var found = false
        for (embed in embeds) {
            found = resolveEmbed(providerName, embed, pageUrl, emitted, subtitleCallback, callback) || found
        }
        return found
    }

    private suspend fun fetchAjaxEmbeds(pageUrl: String, document: Document): List<String> {
        val probes = collectAjaxProbes(document).take(MAX_AJAX_PROBES)
        if (probes.isEmpty()) return emptyList()
        val origin = KitaNontonUtils.originOf(pageUrl) ?: KitaNontonUtils.MAIN_URL
        val endpoints = (listOf("$origin/wp-admin/admin-ajax.php") + adminAjaxRegex.findAll(document.html()).mapNotNull { normalizeUrl(pageUrl, it.value) }).distinct()
        val embeds = linkedSetOf<String>()
        for (endpoint in endpoints) {
            for (probe in probes) {
                val text = runCatching {
                    app.post(
                        url = endpoint,
                        data = mapOf(
                            "action" to "doo_player_ajax",
                            "post" to probe.post,
                            "nume" to probe.nume,
                            "type" to probe.type
                        ),
                        headers = ajaxHeaders(pageUrl),
                        referer = pageUrl
                    ).text
                }.onFailure { Log.e(TAG, "ajax failed endpoint=$endpoint probe=$probe err=${it.message}") }.getOrNull().orEmpty()
                val normalized = decodeMaybe(text)
                runCatching { JSONObject(normalized).optString("embed_url") }.getOrNull()?.takeIf { it.isNotBlank() }?.let { embeds += it }
                embeds += extractEmbeds(pageUrl, normalized)
            }
        }
        return embeds.toList()
    }

    private fun collectAjaxProbes(document: Document): List<AjaxProbe> {
        val probes = linkedSetOf<AjaxProbe>()
        val selectors = listOf(
            ".dooplay_player_option",
            "#playeroptionsul li",
            "ul#playeroptionsul li",
            ".player_sorces li",
            ".player option",
            ".player-option",
            ".server",
            ".server-item",
            "[data-post][data-nume]",
            "[data-id][data-nume]"
        ).joinToString(",")
        document.select(selectors).forEach { element ->
            val post = element.attr("data-post").ifBlank { element.attr("data-id") }.ifBlank { element.attr("data-movie") }
            val nume = element.attr("data-nume").ifBlank { element.attr("data-server") }.ifBlank { element.attr("data-episode") }.ifBlank { "1" }
            val type = element.attr("data-type").ifBlank { if (document.location().contains("tv", true)) "tv" else "movie" }
            if (post.isNotBlank() && nume.isNotBlank()) probes += AjaxProbe(post, nume, type)
        }
        val html = document.html()
        Regex("""(?i)data-(?:post|id)\s*=\s*['\"]?(\d+)['\"]?""").findAll(html).forEach { postMatch ->
            val start = (postMatch.range.first - 240).coerceAtLeast(0)
            val end = (postMatch.range.last + 240).coerceAtMost(html.length)
            val block = html.substring(start, end)
            val post = postMatch.groupValues[1]
            val nume = Regex("""(?i)data-(?:nume|server|episode)\s*=\s*['\"]?(\d+)['\"]?""").find(block)?.groupValues?.getOrNull(1) ?: "1"
            val type = Regex("""(?i)data-type\s*=\s*['\"]?([a-zA-Z0-9_-]+)""").find(block)?.groupValues?.getOrNull(1) ?: "movie"
            probes += AjaxProbe(post, nume, type)
        }
        return probes.toList()
    }

    private suspend fun resolveEmbed(
        providerName: String,
        embedUrl: String,
        referer: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = normalizeUrl(referer, embedUrl) ?: embedUrl
        if (url.isBlank() || !emitted.add(url)) return false
        if (looksLikeMedia(url)) return emitDirect(providerName, "KitaNonton", url, referer, callback)
        val extractorFound = runCatching { loadExtractor(url, referer, subtitleCallback, callback) }
            .onFailure { Log.e(TAG, "loadExtractor failed $url: ${it.message}") }
            .getOrDefault(false)
        if (extractorFound) return true
        val response = runCatching { app.get(url, headers = siteHeaders(referer), referer = referer) }.getOrNull() ?: return false
        collectSubtitles(url, response.document, subtitleCallback)
        var found = false
        extractEmbeds(url, response.text).mapNotNull { normalizeUrl(url, it) }.distinct().take(8).forEach { child ->
            if (child != url) found = resolveEmbed(providerName, child, url, emitted, subtitleCallback, callback) || found
        }
        return found
    }

    private suspend fun emitDirect(
        providerName: String,
        label: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val stream = decodeMaybe(url).trim()
        if (stream.isBlank()) return false
        return try {
            if (stream.contains(".m3u8", true)) {
                val links = generateM3u8(providerName, stream, referer, headers = videoHeaders(referer))
                links.forEach(callback)
                links.isNotEmpty()
            } else {
                callback(newExtractorLink(providerName, label, stream) {
                    this.referer = referer
                    this.headers = videoHeaders(referer)
                    this.quality = Regex("""(\d{3,4})p?""").find(stream)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Qualities.Unknown.value
                })
                true
            }
        } catch (e: Throwable) {
            Log.e(TAG, "emitDirect failed $stream: ${e.message}")
            false
        }
    }

    private fun extractEmbeds(baseUrl: String, text: String): List<String> {
        val normalized = decodeMaybe(text)
        val urls = linkedSetOf<String>()
        iframeRegex.findAll(normalized).mapNotNullTo(urls) { it.groupValues.getOrNull(1) }
        quotedUrlRegex.findAll(normalized).mapNotNullTo(urls) { it.groupValues.getOrNull(1) }
        return urls.mapNotNull { normalizeUrl(baseUrl, it) }.filter { isLikelyEmbedOrMedia(it) }.distinct()
    }

    private suspend fun collectSubtitles(pageUrl: String, document: Document, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[src], a[href$=.srt], a[href$=.vtt], a[href*='subtitle']")
            .mapNotNull { absoluteUrl(pageUrl, it.attr("src").ifBlank { it.attr("href") }) }
            .distinct()
            .forEach { subtitleUrl ->
                val lang = when {
                    subtitleUrl.contains("id", true) || subtitleUrl.contains("ind", true) -> "Indonesian"
                    subtitleUrl.contains("en", true) -> "English"
                    else -> "Subtitle"
                }
                runCatching { subtitleCallback(newSubtitleFile(lang, subtitleUrl)) }
            }
    }

    private fun normalizeUrl(baseUrl: String, raw: String?): String? = absoluteUrl(baseUrl, raw)?.let { decodeMaybe(it) }

    private fun decodeMaybe(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }
        .getOrDefault(value)
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .replace("\\u0026", "&")

    private fun isLikelyEmbedOrMedia(url: String): Boolean {
        val low = url.lowercase()
        if (looksLikeMedia(low)) return true
        if (low.contains("kitanonton2.baby")) return false
        return low.startsWith("http") && (
            low.contains("embed") || low.contains("player") || low.contains("stream") || low.contains("video") || low.contains("file") || low.contains("watch")
        )
    }

    private fun looksLikeMedia(url: String): Boolean {
        val low = url.lowercase()
        return low.contains(".m3u8") || low.contains(".mp4") || low.contains("/master") || low.contains("playlist")
    }
}
