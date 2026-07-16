package com.sad25kag.bondagevalley

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class BondageValley : MainAPI() {
    override var mainUrl = "https://bondagevalley.cc"
    override var name = "BondageValley"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "$mainUrl" to "Semua Video",
        "$mainUrl/videos/latest" to "Video Terbaru",
        "$mainUrl/videos/top" to "Video Terbaik",
        "$mainUrl/videos/model" to "Models"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPagedUrl(request.data, page)
        val document = app.get(url, headers = defaultHeaders, referer = "$mainUrl/").document

        val items = document
            .select("a[href*=/watch/]")
            .mapNotNull { anchor -> anchor.toSearchResult() }
            .filter { it.name.isAllowedTitle() }
            .distinctBy { it.url }
            .take(40)

        val hasNext = document.select("a[href*=page_id=${page + 1}], a[href*='?page_id='], .pagination a, ul.pagination a").isNotEmpty()

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = hasNext || items.isNotEmpty()
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val url = "$mainUrl/search/${query.urlEncoded()}"
        val document = app.get(url, headers = defaultHeaders, referer = "$mainUrl/").document

        val results = document
            .select("a[href*=/watch/]")
            .mapNotNull { anchor -> anchor.toSearchResult() }
            .filter { it.name.isAllowedTitle() }
            .distinctBy { it.url }
            .take(50)

        if (results.isNotEmpty()) return results

        val fallbackUrl = "$mainUrl/search?keyword=${query.urlEncoded()}"
        return app.get(fallbackUrl, headers = defaultHeaders, referer = "$mainUrl/")
            .document
            .select("a[href*=/watch/]")
            .mapNotNull { anchor -> anchor.toSearchResult() }
            .filter { it.name.isAllowedTitle() }
            .distinctBy { it.url }
            .take(50)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders, referer = "$mainUrl/").document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: throw ErrorLoadingException("Judul tidak ditemukan")

        val poster = document.selectFirst("meta[property=og:image], meta[name=twitter:image]")
            ?.attr("content")
            ?.absoluteUrl(url)
            ?: findPosterUrl(document.body(), url)

        val plot = document.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".video-description, .watch-description, .description, #description, p")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        val tags = document
            .select("a[href*=/tags/], a[href*=/tag/], a[href*=/videos/category/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length <= 40 }
            .distinct()

        val actors = document
            .select("a[href*=/model/], a[href*=/models/], a[href*=/user/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length <= 50 }
            .distinct()

        val recommendations = document
            .select("a[href*=/watch/]")
            .mapNotNull { it.toSearchResult() }
            .filter { it.name.isAllowedTitle() }
            .distinctBy { it.url }
            .filterNot { it.url == url }
            .take(20)

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val emitted = linkedSetOf<String>()

        suspend fun emit(rawUrl: String?, label: String = name) {
            val streamUrl = rawUrl
                ?.decodeEscaped()
                ?.absoluteUrl(data)
                ?.takeIf { it.isNotBlank() }
                ?: return

            if (!emitted.add(streamUrl)) return

            callback(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = streamUrl,
                    type = inferLinkType(streamUrl)
                ) {
                    this.referer = data
                    this.quality = getQualityFromName(label)
                    this.headers = streamHeaders(data)
                }
            )
        }

        val response = app.get(data, headers = defaultHeaders, referer = "$mainUrl/")
        val html = response.text
        val document = response.document

        document.select("video source[src], video[src], source[src]").forEach { element ->
            emit(element.attr("src"), "$name - Video")
        }

        val patterns = listOf(
            Regex("""file\s*[:=]\s*['"]([^'"]+\.(?:m3u8|mp4)(?:\?[^'"]*)?)['"]""", RegexOption.IGNORE_CASE),
            Regex("""source\s*[:=]\s*['"]([^'"]+\.(?:m3u8|mp4)(?:\?[^'"]*)?)['"]""", RegexOption.IGNORE_CASE),
            Regex("""video_url\s*[:=]\s*['"]([^'"]+\.(?:m3u8|mp4)(?:\?[^'"]*)?)['"]""", RegexOption.IGNORE_CASE),
            Regex("""videoUrl\s*[:=]\s*['"]([^'"]+\.(?:m3u8|mp4)(?:\?[^'"]*)?)['"]""", RegexOption.IGNORE_CASE),
            Regex("""['"](https?://[^'"]+\.(?:m3u8|mp4)(?:\?[^'"]*)?)['"]""", RegexOption.IGNORE_CASE),
            Regex("""['"]((?:/[^'"]+)?/(?:videos|uploads|media|storage)/[^'"]+\.(?:m3u8|mp4)(?:\?[^'"]*)?)['"]""", RegexOption.IGNORE_CASE)
        )

        patterns.forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                emit(match.groupValues.getOrNull(1), "$name - Direct")
            }
        }

        document.select("iframe[src], iframe[data-src], [data-embed], [data-video], [data-url]").forEach { element ->
            val iframeUrl = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-url") }
                .absoluteUrl(data)

            if (iframeUrl.isNotBlank()) {
                runCatching {
                    loadExtractor(iframeUrl, data, subtitleCallback, callback)
                }.onSuccess { success ->
                    if (success) emitted.add(iframeUrl)
                }
            }
        }

        if (emitted.isNotEmpty()) return true

        return runCatching {
            loadExtractor(data, "$mainUrl/", subtitleCallback, callback)
        }.getOrDefault(false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href").absoluteUrl(mainUrl)
        if (!href.contains("/watch/", true)) return null

        val box = listOfNotNull(
            this,
            parent(),
            parent()?.parent(),
            parent()?.parent()?.parent(),
            parent()?.parent()?.parent()?.parent()
        ).distinct()

        val rawTitle = attr("title").trim()
            .ifBlank { selectFirst("[title]")?.attr("title")?.trim().orEmpty() }
            .ifBlank { selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty() }
            .ifBlank { text().trim() }

        val title = rawTitle
            .replace(Regex("""^\d{1,3}:\d{2}\s*"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (title.isBlank()) return null
        if (!title.isAllowedTitle()) return null

        val poster = box.firstNotNullOfOrNull { findPosterUrl(it, href) }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private fun findPosterUrl(container: Element, pageUrl: String): String? {
        extractImageFromElement(container, pageUrl)?.let { return it }

        container.select("img, source, div, span, a").forEach { element ->
            extractImageFromElement(element, pageUrl)?.let { return it }
        }

        return null
    }

    private fun extractImageFromElement(element: Element, pageUrl: String): String? {
        val attrs = listOf(
            "data-src",
            "data-original",
            "data-lazy-src",
            "data-thumb",
            "data-thumbnail",
            "data-image",
            "data-bg",
            "data-background",
            "poster",
            "src"
        )

        attrs.forEach { attr ->
            val value = element.attr(attr).trim()
            if (value.isValidImageUrl()) return value.absoluteUrl(pageUrl)
        }

        listOf("srcset", "data-srcset").forEach { attr ->
            val value = element.attr(attr).trim()
            val src = value.split(",")
                .map { it.trim().substringBefore(" ").trim() }
                .firstOrNull { it.isValidImageUrl() }
            if (!src.isNullOrBlank()) return src.absoluteUrl(pageUrl)
        }

        val style = element.attr("style")
        Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
            .find(style)
            ?.groupValues
            ?.getOrNull(2)
            ?.trim()
            ?.takeIf { it.isValidImageUrl() }
            ?.let { return it.absoluteUrl(pageUrl) }

        return null
    }

    private fun String.isValidImageUrl(): Boolean {
        if (isBlank()) return false
        if (startsWith("data:", true)) return false
        if (contains("avatar", true) || contains("logo", true) || contains("banner", true)) return false
        if (contains("blank", true) || contains("placeholder", true) || contains("spacer", true)) return false
        return contains(".jpg", true) ||
            contains(".jpeg", true) ||
            contains(".png", true) ||
            contains(".webp", true) ||
            contains("/upload", true) ||
            contains("/uploads", true) ||
            contains("/contents/", true)
    }

    private fun String.isAllowedTitle(): Boolean {
        val blocked = listOf(
            "underage",
            "minor",
            "teen",
            "teens",
            "schoolgirl",
            "school girl",
            "schoolgirls",
            "young girl",
            "little girl",
            "child",
            "kid"
        )

        val lower = lowercase()
        return blocked.none { lower.contains(it) }
    }

    private fun buildPagedUrl(url: String, page: Int): String {
        if (page <= 1) return url
        val clean = url.trimEnd('/')
        return when {
            clean.contains("?") -> "$clean&page_id=$page"
            else -> "$clean?page_id=$page"
        }
    }

    private fun String.absoluteUrl(baseUrl: String): String {
        val value = trim().decodeEscaped()
        return when {
            value.isBlank() -> ""
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> {
                val base = baseUrl.substringBeforeLast("/", "$mainUrl/")
                "$base/$value"
            }
        }
    }

    private fun String?.absoluteUrlOrNull(baseUrl: String): String? {
        return this?.takeIf { it.isNotBlank() }?.absoluteUrl(baseUrl)
    }

    private fun String.decodeEscaped(): String {
        val cleaned = replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("&amp;", "&")
        return runCatching { URLDecoder.decode(cleaned, "UTF-8") }.getOrDefault(cleaned)
    }

    private fun String.urlEncoded(): String = runCatching {
        URLEncoder.encode(this, "UTF-8")
    }.getOrDefault(this)

    private fun inferLinkType(url: String): ExtractorLinkType {
        return when {
            url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            url.contains(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
    }

    private fun streamHeaders(refererUrl: String): Map<String, String> {
        return mapOf(
            "Accept" to "*/*",
            "User-Agent" to USER_AGENT,
            "Referer" to refererUrl,
            "Origin" to mainUrl
        )
    }

    private val defaultHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )
}
