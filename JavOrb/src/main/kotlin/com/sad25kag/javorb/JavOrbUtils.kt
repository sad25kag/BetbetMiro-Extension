package com.sad25kag.javorb

import com.lagradost.cloudstream3.utils.httpsify
import org.jsoup.Jsoup
import java.net.URI
import java.util.Base64

object JavOrbUtils {
    const val BASE_URL = "https://javorb.com"

    fun cleanText(value: String?): String {
        return value.orEmpty()
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun cleanHtml(value: String?): String {
        return Jsoup.unescape(value.orEmpty())
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u002F", "/")
            .trim()
    }

    fun normalizeUrl(value: String?, baseUrl: String = BASE_URL): String? {
        val raw = cleanHtml(value)
            .trim('"', '\'', ' ', '\n', '\r', '\t')
            .takeIf { it.isNotBlank() } ?: return null

        val decoded = decodeBase64(raw)
            ?.takeIf { it.startsWith("http", true) || it.startsWith("//") || it.startsWith("/") }

        val candidate = decoded ?: raw
        val fixed = when {
            candidate.startsWith("//") -> "https:$candidate"
            candidate.startsWith("http://", true) || candidate.startsWith("https://", true) -> candidate
            candidate.startsWith("/") -> getBaseUrl(baseUrl).trimEnd('/') + candidate
            candidate.startsWith("?") -> baseUrl.substringBefore("?") + candidate
            else -> runCatching { URI(baseUrl).resolve(candidate).toString() }.getOrNull()
        } ?: return null

        return httpsify(fixed)
    }

    fun decodeBase64(value: String?): String? {
        val clean = value
            ?.trim()
            ?.trim('"', '\'', ' ', '\n', '\r', '\t')
            ?.replace("-", "+")
            ?.replace("_", "/")
            ?: return null

        if (clean.length < 8 || clean.any { it.isWhitespace() }) return null
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return runCatching { String(Base64.getDecoder().decode(padded), Charsets.UTF_8) }.getOrNull()
            ?.let { cleanHtml(it) }
    }

    fun firstYear(value: String?): Int? {
        return Regex("""\b(19\d{2}|20\d{2})\b""").find(value.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    fun firstDurationMinutes(value: String?): Int? {
        return Regex("""(?i)\b(\d{1,3})\s*(?:min|mins|menit|m)\b""").find(value.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    fun dvdId(value: String?): String? {
        return Regex("""\b[A-Z]{2,10}-\d{2,6}\b""").find(value.orEmpty().uppercase())?.value
    }

    fun getBaseUrl(url: String): String {
        return runCatching { URI(url).let { "${it.scheme}://${it.host}" } }.getOrNull() ?: BASE_URL
    }

    fun isContentUrl(url: String): Boolean {
        return url.startsWith(BASE_URL, true) && url.contains("/video/", true)
    }

    fun isDirectVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".webm")
    }

    fun isSubtitleUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".srt") || lower.contains(".vtt") || lower.contains(".ass")
    }

    fun isNoiseUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/ads") || lower.contains("doubleclick") || lower.contains("googlesyndication") ||
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif") ||
            lower.endsWith(".css") || lower.endsWith(".js") || lower.contains("/favicon")
    }
}
