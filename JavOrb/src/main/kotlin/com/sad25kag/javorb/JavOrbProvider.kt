package com.sad25kag.javorb

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

class JavOrbProvider : MainAPI() {
    override var mainUrl = JavOrbUtils.BASE_URL
    override var name = "JavOrb"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie)

    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "/videos/paling-baru" to "Paling Baru",
        "/videos/paling-dilihat" to "Paling Dilihat",
        "/videos/top-rating" to "Top Rating",
        "/videos/jav-sub-indo" to "JAV Sub Indo",
        "/videos/jav-english-sub" to "JAV English Sub",
        "/videos/jav-no-sub" to "JAV No Sub"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val candidates = buildPageCandidates(request.data, page)
        for (url in candidates) {
            val document = runCatching {
                app.get(url, headers = baseHeaders, referer = mainUrl).document
            }.getOrNull() ?: continue

            val items = JavOrbParser.parseListing(document, url).map { card ->
                newMovieSearchResponse(card.title, card.url, TvType.Movie) {
                    posterUrl = card.posterUrl
                    this.year = JavOrbUtils.firstYear(card.title)
                }
            }

            if (items.isNotEmpty()) {
                return newHomePageResponse(
                    request.name,
                    items,
                    hasNext = hasNextPage(document, page) || items.size >= 12
                )
            }
        }

        return newHomePageResponse(request.name, emptyList(), hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val searchCandidates = listOf(
            "$mainUrl/search/$encoded",
            "$mainUrl/?s=$encoded",
            "$mainUrl/?search=$encoded",
            "$mainUrl/videos/paling-baru"
        )

        val results = linkedMapOf<String, SearchResponse>()
        for (url in searchCandidates) {
            val document = runCatching {
                app.get(url, headers = baseHeaders, referer = mainUrl).document
            }.getOrNull() ?: continue

            JavOrbParser.parseListing(document, url)
                .filter { card -> card.title.contains(keyword, ignoreCase = true) || url.contains("search", true) || url.contains("?s=", true) }
                .forEach { card ->
                    results[card.url] = newMovieSearchResponse(card.title, card.url, TvType.Movie) {
                        posterUrl = card.posterUrl
                        this.year = JavOrbUtils.firstYear(card.title)
                    }
                }

            if (results.isNotEmpty()) break
        }

        return results.values.take(60)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = JavOrbUtils.normalizeUrl(url, mainUrl) ?: return null
        val response = runCatching {
            app.get(pageUrl, headers = baseHeaders, referer = mainUrl)
        }.getOrNull() ?: return null

        val detail = JavOrbParser.parseDetail(response.document, pageUrl) ?: return null

        return newMovieLoadResponse(detail.title, pageUrl, TvType.Movie, pageUrl) {
            posterUrl = detail.posterUrl
            plot = detail.description
            year = detail.year
            duration = detail.duration ?: 0
            tags = detail.tags
            addActors(detail.actors)
            recommendations = JavOrbParser.parseListing(response.document, pageUrl).map { card ->
                newMovieSearchResponse(card.title, card.url, TvType.Movie) {
                    posterUrl = card.posterUrl
                    this.year = JavOrbUtils.firstYear(card.title)
                }
            }.take(12)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = JavOrbUtils.normalizeUrl(data, mainUrl) ?: data
        val visited = linkedSetOf<String>()
        var emitted = false

        val response = runCatching {
            app.get(pageUrl, headers = baseHeaders, referer = mainUrl)
        }.getOrNull() ?: return false

        val firstWave = collectPlayerCandidates(response.document, response.text, pageUrl)
        for (candidate in firstWave) {
            if (resolveCandidate(candidate, pageUrl, visited, subtitleCallback, callback)) {
                emitted = true
            }
        }

        return emitted
    }

    private suspend fun resolveCandidate(
        rawCandidate: String,
        referer: String,
        visited: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int = 0
    ): Boolean {
        if (depth > 2) return false
        val candidate = JavOrbUtils.normalizeUrl(rawCandidate, referer) ?: return false
        if (!visited.add(candidate) || JavOrbUtils.isNoiseUrl(candidate)) return false

        if (JavOrbUtils.isSubtitleUrl(candidate)) {
            subtitleCallback(newSubtitleFile("Indonesian", candidate))
            return false
        }

        if (JavOrbUtils.isDirectVideoUrl(candidate)) {
            emitDirect(candidate, referer, callback)
            return true
        }

        var emitted = false
        runCatching {
            loadExtractor(candidate, referer, subtitleCallback) { link ->
                emitted = true
                callback(link)
            }
        }

        if (emitted) return true

        val playerResponse = runCatching {
            app.get(candidate, headers = baseHeaders + mapOf("Referer" to referer), referer = referer, timeout = 15000L)
        }.getOrNull() ?: return false

        val nested = collectPlayerCandidates(playerResponse.document, playerResponse.text, candidate)
        for (next in nested) {
            if (resolveCandidate(next, candidate, visited, subtitleCallback, callback, depth + 1)) {
                emitted = true
            }
        }

        return emitted
    }

    private fun collectPlayerCandidates(document: Document, html: String, baseUrl: String): List<String> {
        val candidates = linkedSetOf<String>()

        document.select("option[value], select option[value], .mobius option[value], .mirror option[value], [data-server][value]").forEach { option ->
            expandEncodedValue(option.attr("value")).forEach { candidates.add(it) }
        }

        val attrSelector = listOf(
            "iframe[src]",
            "iframe[data-src]",
            "video[src]",
            "video source[src]",
            "source[src]",
            "track[src]",
            "a[href*='/player/']",
            "a[href*='/embed/']",
            "a[href*=dailymotion]",
            "a[href*=ok.ru]",
            "a[href*=stream]",
            "[data-src]",
            "[data-url]",
            "[data-embed]",
            "[data-iframe]",
            "[data-player]"
        ).joinToString(",")

        document.select(attrSelector).forEach { element ->
            listOf("src", "data-src", "href", "data-url", "data-embed", "data-iframe", "data-player").forEach { attr ->
                val value = element.attr(attr).takeIf { it.isNotBlank() } ?: return@forEach
                expandEncodedValue(value).forEach { candidates.add(it) }
            }
        }

        Regex("""<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { it.groupValues.getOrNull(1)?.let { url -> candidates.add(url) } }

        Regex("""(?i)(?:file|source|src|url|video)\s*[:=]\s*["'](https?://[^"']+)["']""")
            .findAll(html)
            .forEach { it.groupValues.getOrNull(1)?.let { url -> candidates.add(url) } }

        Regex("""["'](https?://[^"']+\.(?:m3u8|mp4|webm|srt|vtt)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { it.groupValues.getOrNull(1)?.let { url -> candidates.add(url) } }

        Regex("""["'](https?://[^"']+(?:/embed/|/player/|dailymotion\.com|ok\.ru|stream|vid|cloud|drive)[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { it.groupValues.getOrNull(1)?.let { url -> candidates.add(url) } }

        Regex("""["']([A-Za-z0-9+/=_-]{24,})["']""")
            .findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .mapNotNull { JavOrbUtils.decodeBase64(it) }
            .forEach { decoded -> expandEncodedValue(decoded).forEach { candidates.add(it) } }

        return candidates
            .mapNotNull { JavOrbUtils.normalizeUrl(it, baseUrl) }
            .filterNot { JavOrbUtils.isNoiseUrl(it) }
            .distinct()
    }

    private fun expandEncodedValue(value: String): List<String> {
        val clean = JavOrbUtils.cleanHtml(value)
        val decoded = JavOrbUtils.decodeBase64(clean)
        val values = listOfNotNull(clean, decoded).flatMap { raw ->
            val doc = Jsoup.parse(raw)
            val fromIframe = doc.select("iframe[src], iframe[data-src], video[src], source[src], track[src]")
                .flatMap { element -> listOf(element.attr("src"), element.attr("data-src")) }
                .filter { it.isNotBlank() }
            val fromRegex = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
                .findAll(raw)
                .map { it.value.trimEnd(',', ';', ')', ']') }
                .toList()
            fromIframe + fromRegex + listOf(raw)
        }
        return values.distinct()
    }

    private fun buildPageCandidates(path: String, page: Int): List<String> {
        val base = JavOrbUtils.normalizeUrl(path, mainUrl)?.trimEnd('/') ?: mainUrl
        if (page <= 1) return listOf(base, "$base/page=1", "$base?page=1")
        return listOf(
            "$base/page=$page",
            "$base?page=$page",
            "$base/page/$page",
            "$base/$page"
        )
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.select("a[href], .pagination a, .pages a, .next a, a.next").any { element ->
            val text = JavOrbUtils.cleanText(element.text())
            val href = element.attr("href")
            text.equals("Next", true) || text == "›" || text == "»" ||
                Regex("""\b${page + 1}\b""").containsMatchIn(text) || href.contains("page=${page + 1}") || href.contains("/page/${page + 1}")
        }
    }

    private suspend fun emitDirect(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val type = if (url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        callback(
            newExtractorLink(name, name, url, type) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
