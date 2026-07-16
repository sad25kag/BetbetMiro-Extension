package com.sad25kag.camwh

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class CamWh : MainAPI() {
    override var mainUrl = "https://camwh.com"

    private val baseReferer = "$mainUrl/"
    override var name = "CamWh"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "$mainUrl/latest-updates/" to "Video Terbaru",
        "$mainUrl/top-rated/" to "Terkenal",
        "$mainUrl/most-popular/" to "Banyak Ditonton"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            buildPagedUrl(request.data, page),
            headers = defaultHeaders,
            referer = baseReferer
        ).document

        val items = document.select("div.item, .list-videos .item, .thumb, .video-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/search/$encodedQuery/?mode=async&function=get_block&block_id=list_videos_videos_list_search_result&q=$encodedQuery&category_ids=&sort_by=&from_videos=$page&from_albums=1"

        val document = app.get(
            searchUrl,
            headers = defaultHeaders,
            referer = "$mainUrl/search/$encodedQuery/"
        ).document

        val results = document.select("div.item, .list-videos .item, .thumb, .video-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val normalizedUrl = normalizeUrl(url) ?: throw ErrorLoadingException("URL CamWh tidak valid.")

        val document = app.get(
            normalizedUrl,
            headers = defaultHeaders,
            referer = baseReferer
        ).document

        val title = document.selectFirst("div.headline h1, h1")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: throw ErrorLoadingException("Judul tidak ditemukan.")

        val poster = fixUrlNull(
            document.selectFirst("div.fp-poster img, meta[property=og:image], link[rel=image_src]")
                ?.let { element ->
                    when (element.tagName()) {
                        "meta" -> element.attr("content")
                        "link" -> element.attr("href")
                        else -> element.attr("src").ifBlank { element.attr("data-original") }
                    }
                }
        )

        val description = document.selectFirst("div.item:contains(Description:) em, meta[name=description], meta[property=og:description]")
            ?.let { element ->
                if (element.tagName() == "meta") element.attr("content") else element.text()
            }
            ?.trim()
            ?.ifBlank { null }

        val actors = document.select("div.item:contains(Tags:) a, .tags a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val tags = document.select("div.item:contains(Categories:) a, .categories a")
            .map { translateTag(it.text().trim()) }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = document.select("div.list-videos div.item, .related-videos div.item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newMovieLoadResponse(title, normalizedUrl, TvType.NSFW, normalizedUrl) {
            this.posterUrl = poster
            this.plot = description
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
        val emitted = linkedSetOf<String>()

        suspend fun emitDirect(rawUrl: String?, label: String = name) {
            val videoUrl = rawUrl
                ?.resolveCamWhVideoUrl()
                ?.takeIf { it.isNotBlank() }
                ?: return

            if (!emitted.add(videoUrl)) return

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = videoUrl,
                    type = inferType(videoUrl)
                ) {
                    this.referer = baseReferer
                    this.quality = getQualityFromName(label)
                    this.headers = streamHeaders()
                }
            )
        }


        suspend fun extractFromHtml(html: String) {
            val patterns = listOf(
                Regex("""video_alt_url\d*\s*[:=]\s*['"]([^'"]+)""", RegexOption.IGNORE_CASE),
                Regex("""video_url\d*\s*[:=]\s*['"]([^'"]+)""", RegexOption.IGNORE_CASE),
                Regex("""contentUrl"\s*:\s*"([^"]+/get_file/3/[^"]+)""", RegexOption.IGNORE_CASE),
                Regex("""file\s*[:=]\s*['"]([^'"]+\.(?:mp4|m3u8)[^'"]*)""", RegexOption.IGNORE_CASE),
                Regex("""source\s*[:=]\s*['"]([^'"]+\.(?:mp4|m3u8)[^'"]*)""", RegexOption.IGNORE_CASE),
                Regex("""['"](https?://[^'"]+/get_file/3/[^'"]+)['"]""", RegexOption.IGNORE_CASE),
                Regex("""['"](https?://[^'"]+\.(?:mp4|m3u8)(?:\?[^'"]*)?)['"]""", RegexOption.IGNORE_CASE)
            )

            for (pattern in patterns) {
                for (match in pattern.findAll(html)) {
                    emitDirect(match.groupValues.getOrNull(1), "$name - Direct")
                }
            }
        }

        val normalizedData = normalizeUrl(data) ?: data

        val response = app.get(
            normalizedData,
            headers = defaultHeaders,
            referer = baseReferer
        )
        val document = response.document

        extractFromHtml(response.text)

        for (element in document.select("video source[src], video[src], source[src]")) {
            emitDirect(element.attr("src"), "$name - Video")
        }

        for (element in document.select("iframe[src], iframe[data-src], [data-video], [data-url]")) {
            val iframeUrl = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-url") }

            if (iframeUrl.isNotBlank()) {
                try {
                    loadExtractor(fixUrl(iframeUrl), data, subtitleCallback, callback)
                } catch (_: Exception) {
                    // Ignore broken iframe fallback and continue direct extraction.
                }
            }
        }

        if (emitted.isNotEmpty()) return true

        val webview = WebViewResolver(
            interceptUrl = Regex(""".*/get_file/.*"""),
            userAgent = USER_AGENT,
            useOkhttp = false
        )

        var capturedFileUrl = ""

        webview.resolveUsingWebView(
            url = normalizedData,
            referer = baseReferer,
            requestCallBack = { request ->
                val currentUrl = request.url.toString()

                if (currentUrl.contains("/get_file/")) {
                    capturedFileUrl = currentUrl
                    true
                } else {
                    false
                }
            }
        )

        if (capturedFileUrl.isNotBlank()) {
            val redirected = app.get(
                capturedFileUrl,
                headers = streamHeaders(),
                referer = baseReferer,
                allowRedirects = false
            ).headers["Location"] ?: capturedFileUrl

            emitDirect(redirected, "$name - WebView")
        }

        return emitted.isNotEmpty()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val title = anchor.attr("title").trim()
            .ifBlank { selectFirst(".title, strong, .video-title")?.text()?.trim().orEmpty() }
            .ifBlank { selectFirst("img")?.attr("alt")?.trim().orEmpty() }

        if (title.isBlank()) return null

        val href = normalizeUrl(anchor.attr("href")) ?: return null
        val img = selectFirst("img")
        val poster = fixUrlNull(
            img?.attr("data-original")
                ?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-webp")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private fun normalizeUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return null

        val url = if (trimmed.startsWith("http", ignoreCase = true)) {
            trimmed
        } else {
            "$mainUrl/${trimmed.trimStart('/')}"
        }

        val host = runCatching {
            java.net.URI(url).host?.lowercase()
        }.getOrNull() ?: return null

        return if (
            host == "camwh.com" ||
            host.endsWith(".camwh.com")
        ) {
            url
        } else {
            null
        }
    }

    private fun buildPagedUrl(rawUrl: String, page: Int): String {
        if (page <= 1) return rawUrl
        val clean = rawUrl.trimEnd('/')
        return when {
            clean.contains("from=") -> clean.replace(Regex("""from=\d+"""), "from=$page")
            clean.contains("?") -> "$clean&from=$page"
            else -> "$clean/$page/"
        }
    }

    private fun inferType(url: String): ExtractorLinkType {
        return when {
            url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }
    }

    private fun streamHeaders(): Map<String, String> {
        return mapOf(
            "Accept" to "*/*",
            "User-Agent" to USER_AGENT,
            "Referer" to baseReferer,
            "Origin" to mainUrl,
            "Range" to "bytes=0-"
        )
    }

    private suspend fun String.resolveCamWhVideoUrl(): String? {
        val decoded = decodeEscapedUrl()
            .removePrefix("function/0/")
            .trim()

        if (decoded.isBlank()) return null
        if (decoded.contains("videos_screenshots", ignoreCase = true)) return null
        if (decoded.contains("/screenshots/", ignoreCase = true)) return null
        if (decoded.endsWith(".jpg", ignoreCase = true) || decoded.endsWith(".png", ignoreCase = true)) return null

        val fixed = fixUrl(decoded)
        if (!fixed.contains("/get_file/", ignoreCase = true)) return fixed

        return runCatching {
            app.get(
                fixed,
                headers = streamHeaders(),
                referer = baseReferer,
                allowRedirects = false
            ).headers["Location"] ?: fixed
        }.getOrDefault(fixed)
    }

    private fun String.decodeEscapedUrl(): String {
        return replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("&amp;", "&")
            .let { value ->
                runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
            }
    }

    private fun translateTag(tag: String): String {
        return when (tag.lowercase()) {
            "latest videos" -> "Video Terbaru"
            "top rated videos" -> "Rating Tertinggi"
            "most viewed videos" -> "Paling Dilihat"
            "webcam" -> "Webcam"
            "amateur" -> "Amateur"
            "solo" -> "Solo"
            "public" -> "Public"
            "blonde" -> "Blonde"
            "brunette" -> "Brunette"
            else -> tag
        }
    }

    private val defaultHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "User-Agent" to USER_AGENT,
        "Referer" to baseReferer
    )
}
