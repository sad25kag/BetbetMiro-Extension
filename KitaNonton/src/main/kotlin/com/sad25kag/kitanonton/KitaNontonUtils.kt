package com.kitanonton

import com.lagradost.cloudstream3.TvType
import java.net.URI
import java.net.URLEncoder

object KitaNontonUtils {
    const val MAIN_URL = "https://kitanonton2.baby"
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36"

    val mainPage = arrayOf(
        "/page/%d/" to "Update Terbaru",
        "/movie/page/%d/" to "Movie",
        "/tv-series/page/%d/" to "TV Series",
        "/genre/action/page/%d/" to "Action",
        "/genre/adventure/page/%d/" to "Adventure",
        "/genre/animation/page/%d/" to "Animation",
        "/genre/comedy/page/%d/" to "Comedy",
        "/genre/crime/page/%d/" to "Crime",
        "/genre/drama/page/%d/" to "Drama",
        "/genre/family/page/%d/" to "Family",
        "/genre/fantasy/page/%d/" to "Fantasy",
        "/genre/horror/page/%d/" to "Horror",
        "/genre/mystery/page/%d/" to "Mystery",
        "/genre/romance/page/%d/" to "Romance",
        "/genre/thriller/page/%d/" to "Thriller"
    )

    fun siteHeaders(referer: String = MAIN_URL): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to referer
    )

    fun ajaxHeaders(pageUrl: String): Map<String, String> = siteHeaders(pageUrl) + mapOf(
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Origin" to (originOf(pageUrl) ?: MAIN_URL),
        "X-Requested-With" to "XMLHttpRequest"
    )

    fun videoHeaders(referer: String): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Referer" to referer
        )
        originOf(referer)?.let { headers["Origin"] = it }
        return headers
    }

    fun cleanText(value: String?): String = value.orEmpty()
        .replace("\u00a0", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun originOf(url: String?): String? = runCatching {
        val uri = URI(url.orEmpty())
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        "$scheme://$host$port"
    }.getOrNull()

    fun absoluteUrl(baseUrl: String, rawValue: String?): String? {
        val raw = rawValue.orEmpty().trim().trim('\'', '"', ' ', ',', ';')
        if (raw.isBlank()) return null
        val low = raw.lowercase()
        if (low == "#" || low == "about:blank" || low == "null" || low == "undefined") return null
        if (low.startsWith("javascript:") || low.startsWith("data:") || low.startsWith("blob:")) return null
        if (raw.startsWith("//")) return "https:$raw"
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val origin = originOf(baseUrl) ?: MAIN_URL
        if (raw.startsWith("/")) return origin.trimEnd('/') + raw
        return baseUrl.substringBeforeLast('/', origin).trimEnd('/') + "/" + raw
    }

    fun pageUrl(path: String, page: Int): String {
        val normalized = if (path.startsWith("http")) path else MAIN_URL.trimEnd('/') + "/" + path.trimStart('/')
        return if (normalized.contains("%d")) {
            if (page <= 1) normalized
                .replace("/page/%d/", "/")
                .replace("page/%d/", "")
                .replace("%d", "")
                .replace(Regex("(?<!:)//+"), "/")
                .replace("https:/", "https://")
                .trimEnd('/') + "/"
            else normalized.replace("%d", page.toString())
        } else if (page <= 1) normalized else normalized.trimEnd('/') + "/page/$page/"
    }

    fun searchUrl(query: String): String = MAIN_URL.trimEnd('/') + "/?s=" + URLEncoder.encode(query, "UTF-8")

    fun typeFrom(url: String, title: String): TvType {
        val low = "$url $title".lowercase()
        return if (low.contains("tv-series") || low.contains("tvshows") || low.contains("series") || low.contains("episode")) TvType.TvSeries else TvType.Movie
    }

    fun isValidContentUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().removePrefix("www.")
        if (host != "kitanonton2.baby") return false
        val path = uri.path.orEmpty().trim('/')
        if (path.isBlank()) return false
        val parts = path.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return false
        val first = parts.first().lowercase()
        val last = parts.last().lowercase()
        if (first in setOf("genre", "country", "year", "tag", "quality", "page", "category", "search")) return false
        if (last == "page" || last.toIntOrNull() != null) return false
        if (last.endsWith(".jpg") || last.endsWith(".png") || last.endsWith(".webp")) return false
        return true
    }
}
