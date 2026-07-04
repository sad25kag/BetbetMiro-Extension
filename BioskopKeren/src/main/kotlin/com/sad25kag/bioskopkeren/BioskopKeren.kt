package com.sad25kag.bioskopkeren

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class BioskopKeren : MainAPI() {
    override var mainUrl = "http://134.209.20.140"
    override var name = "BioskopKeren"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"

    override val supportedTypes = setOf(TvType.Movie)

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private fun bkLog(message: String) {
        println("BIOSKOPKEREN-Z4 $message")
    }

    override val mainPage = mainPageOf(
        "best-rating/" to "Best Rating",
        "genre/action/" to "Action",
        "genre/adventure/" to "Adventure",
        "genre/animation/" to "Animation",
        "genre/comedy/" to "Comedy",
        "genre/crime/" to "Crime",
        "genre/documentary/" to "Documentary",
        "genre/drama/" to "Drama",
        "genre/family/" to "Family",
        "genre/fantasy/" to "Fantasy",
        "genre/history/" to "History",
        "genre/horror/" to "Horror",
        "genre/music/" to "Music",
        "genre/mystery/" to "Mystery",
        "genre/romance/" to "Romance",
        "genre/science-fiction/" to "Science Fiction",
        "genre/thriller/" to "Thriller",
        "genre/war/" to "War"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = buildPageUrl(request.data, page)
        val document = app.get(pageUrl, headers = siteHeaders, referer = "$mainUrl/").document
        val cards = parseCards(document).distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, cards, isHorizontalImages = false),
            hasNext = hasNextPage(document, page)
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded&post_type%5B%5D=post&post_type%5B%5D=tv",
            "$mainUrl/?s=$encoded"
        )

        val results = linkedMapOf<String, SearchResponse>()
        urls.forEach { url ->
            val document = runCatching {
                app.get(url, headers = siteHeaders, referer = "$mainUrl/").document
            }.getOrNull() ?: return@forEach

            parseCards(document).forEach { item ->
                if (
                    item.name.contains(cleanQuery, ignoreCase = true) ||
                    item.url.contains(cleanQuery.slugQuery(), ignoreCase = true)
                ) {
                    results[item.url] = item
                }
            }

            if (results.isNotEmpty()) return@forEach
        }

        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = normalizeProviderUrl(fixUrl(url))
        val document = app.get(fixedUrl, headers = siteHeaders, referer = "$mainUrl/").document

        val titleElem = document.selectFirst("h1.entry-title, .gmr-movie-data h1.entry-title, meta[property=og:title], meta[name=title]")
        val titleRaw = titleElem?.let { if (it.hasAttr("content")) it.attr("content") else it.text() } ?: ""
        val title = if (titleRaw.isNotBlank() && !titleRaw.isUiText()) titleRaw.cleanDetailTitle() else fixedUrl.slugTitle().cleanDetailTitle()

        val posterElem = document.selectFirst("meta[property=og:image], meta[name=twitter:image], .gmr-movie-data img, .entry-content img, img.wp-post-image")
        val posterRaw = posterElem?.let { if (it.hasAttr("content")) it.attr("content") else it.getImageAttr() }
        val poster = posterRaw?.let { resolveUrl(it, fixedUrl) ?: fixUrlNull(it) }?.takeIf { !isBadImage(it) }

        val plot = extractPlot(document)
        val tags = document.select(".content-moviedata a[href*='/genre/'], .gmr-moviedata a[href*='/genre/']")
            .map { it.text().cleanTitle() }
            .filter { it.isNotBlank() && !it.isUiText() }
            .distinct()
            .take(20)

        val year = document.selectFirst(".content-moviedata a[href*='/year/'], .gmr-moviedata a[href*='/year/']")
            ?.text()?.toIntOrNull() ?: extractYear(title) ?: extractYear(document.text())

        val recommendations = parseCards(document)
            .filter { it.url != fixedUrl }
            .distinctBy { it.url }
            .take(24)

        return newMovieLoadResponse(title, fixedUrl, TvType.Movie, fixedUrl) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = normalizeProviderUrl(fixUrl(data))
        bkLog("watchUrl=$watchUrl")

        val document = runCatching {
            app.get(watchUrl, headers = siteHeaders, referer = "$mainUrl/", timeout = 30L).document
        }.getOrNull() ?: return false

        val candidates = linkedSetOf<String>()
        candidates.addAll(collectPlayerUrls(document, watchUrl))
        candidates.addAll(collectServerPageUrls(document, watchUrl))

        val vidhide = candidates.filter { it.contains("vidhide", ignoreCase = true) }
        candidates.removeAll(vidhide.toSet())
        candidates.addAll(0, vidhide)

        val iframeUrls = linkedSetOf<String>()
        candidates.forEach { url ->
            if (url != watchUrl) iframeUrls.add(url)
        }

        val extraCandidates = linkedSetOf<String>()
        iframeUrls.forEach { pageUrl ->
            val serverDocument = runCatching {
                app.get(pageUrl, headers = siteHeaders, referer = watchUrl, timeout = 30L).document
            }.getOrNull() ?: return@forEach

            extraCandidates.addAll(collectPlayerUrls(serverDocument, pageUrl))
            extraCandidates.addAll(collectServerPageUrls(serverDocument, pageUrl))
            extraCandidates.addAll(extractMediaUrls(serverDocument.html(), pageUrl))
            extraCandidates.addAll(extractMediaUrls(serverDocument.text(), pageUrl))
        }

        val allUrls = linkedSetOf<String>()
        allUrls.addAll(candidates)
        allUrls.addAll(extraCandidates)

        bkLog("candidateCount=${allUrls.size}")
        allUrls.forEach { bkLog("candidate=$it") }

        var emitted = false
        allUrls.filterNot { isBadPlaybackUrl(it) }.distinct().forEach { candidate ->
            val ok = resolveIframeWithExtractor(candidate, watchUrl, subtitleCallback) { link ->
                emitted = true
                callback(link)
            }
            bkLog("candidateResult url=$candidate ok=$ok")
        }

        bkLog("loadLinksResult_emitted=$emitted")
        return emitted
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveIframeWithExtractor(
        iframeUrl: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val extractorReferer = getExtractorReferer(iframeUrl, pageUrl)
        bkLog("resolveIframe url=$iframeUrl host=${safeHost(iframeUrl)} referer=$extractorReferer")

        if (tryLoadExtractorWithReferers(iframeUrl, listOf(extractorReferer, pageUrl, iframeUrl, "$mainUrl/"), subtitleCallback, callback)) {
            bkLog("resolveIframe directExtractor=true url=$iframeUrl")
            return true
        }

        val iframeResponse = runCatching {
            app.get(iframeUrl, headers = siteHeaders, referer = extractorReferer, timeout = 30L)
        }.getOrNull() ?: return false

        val iframeHtml = iframeResponse.text.decodeEscaped()
        val iframeDocument = Jsoup.parse(iframeHtml, iframeUrl)

        val nestedPlayers = linkedSetOf<String>()
        nestedPlayers.addAll(collectPlayerUrls(iframeDocument, iframeUrl))
        nestedPlayers.addAll(collectServerPageUrls(iframeDocument, iframeUrl))
        nestedPlayers.addAll(extractMediaUrls(iframeHtml, iframeUrl))

        iframeDocument.select("#servers a[data-url], a[data-url*='/embed/'], form[action*='/embed/']")
            .mapNotNull { element ->
                listOf("data-url", "action", "href").mapNotNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }.firstOrNull()
            }
            .mapNotNull { resolveUrl(it, iframeUrl) }
            .filterNot { isBadPlaybackUrl(it) }
            .forEach { nestedPlayers.add(it) }

        var found = false
        nestedPlayers.distinct().filter { it != iframeUrl }.forEach { nested ->
            found = tryLoadExtractorWithReferers(nested, listOf(getExtractorReferer(nested, iframeUrl), iframeUrl, extractorReferer, pageUrl), subtitleCallback, callback) || found
        }
        return found
    }

    private suspend fun tryLoadExtractorWithReferers(
        url: String,
        referers: List<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixed = resolveUrl(url, mainUrl) ?: return false
        if (isBadPlaybackUrl(fixed)) return false

        return referers.mapNotNull { it.takeIf(String::isNotBlank) }.distinct().any { referer ->
            var emitted = false
            val countingCallback: (ExtractorLink) -> Unit = { link -> emitted = true; callback(link) }
            val loaded = runCatching { loadExtractor(fixed, referer, subtitleCallback, countingCallback) }.getOrDefault(false)
            bkLog("extractorResult url=$fixed referer=$referer loaded=$loaded emitted=$emitted")
            emitted
        }
    }

    private fun extractMediaUrls(text: String, pageUrl: String): List<String> {
        val cleaned = text.decodeEscaped()
        val results = linkedSetOf<String>()

        Regex("https?://[^\"'< >s\\]+?\.(?:m3u8|mp4|webm|mkv)(?:\?[^\"'< >s\\]*)?", RegexOption.IGNORE_CASE)
            .findAll(cleaned).map { it.value }.mapNotNull { resolveUrl(it, pageUrl) }.filterNot { isBadPlaybackUrl(it) }.forEach { results.add(it) }

        Regex("//[^\"'< >s\\]+?\.(?:m3u8|mp4|webm|mkv)(?:\?[^\"'< >s\\]*)?", RegexOption.IGNORE_CASE)
            .findAll(cleaned).map { "https:${it.value}" }.mapNotNull { resolveUrl(it, pageUrl) }.filterNot { isBadPlaybackUrl(it) }.forEach { results.add(it) }

        return results.toList()
    }

    private fun getExtractorReferer(iframeUrl: String, pageUrl: String): String {
        val host = runCatching { URI(iframeUrl).host.orEmpty().lowercase() }.getOrDefault("")
        return if (host == "vidhide.org" || host.endsWith(".vidhide.org")) "$mainUrl/" else pageUrl
    }

    private fun safeHost(url: String): String {
        return runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        document.select("article.item-infinite, article.item, .gmr-box-content:has(h2.entry-title a), .item:has(h2.entry-title a)").forEach { element ->
            element.toSearchResult()?.let { results[it.url] = it }
        }
        if (results.isEmpty()) {
            document.select("h2.entry-title a[href], a[rel=bookmark][href]:has(img)").forEach { element ->
                element.toSearchResult()?.let { results[it.url] = it }
            }
        }
        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) this else selectFirst("h2.entry-title a[href], .entry-title a[href], a[rel=bookmark][href]:has(img)") ?: return null
        val href = normalizeProviderUrl(resolveUrl(anchor.attr("href"), mainUrl) ?: return null)
        if (!isProviderUrl(href) || isBlockedUrl(href)) return null

        val titleCandidates = listOfNotNull(selectFirst("h2.entry-title a")?.text(), selectFirst(".entry-title a")?.text(), anchor.text(), anchor.attr("title"), selectFirst("img[alt]")?.attr("alt"))
        val title = titleCandidates.mapNotNull { it.cleanTitle().takeIf { t -> t.isNotBlank() && !t.isUiText() } }.firstOrNull() ?: return null

        val poster = extractPosterUrl(this, anchor)

        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = poster
            year = extractYear(title) ?: extractYear(text())
        }
    }

    private fun collectPlayerUrls(document: Document, pageUrl: String): List<String> {
        val results = linkedSetOf<String>()
        // Simplified robust selector
        document.select("iframe[src], iframe[data-src], embed[src], source[src], video[src]").forEach { element ->
            listOf("src", "data-src", "data").mapNotNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }.mapNotNull { resolveUrl(it, pageUrl) }.forEach { results.add(it) }
        }
        return results.toList()
    }

    private fun collectServerPageUrls(document: Document, pageUrl: String): List<String> {
        return document.select(".server a[href], .mirror a[href], .player-nav a[href]").mapNotNull { resolveUrl(it.attr("href"), pageUrl) }
            .map { normalizeProviderUrl(it) }
            .filter { isProviderUrl(it) && !isBlockedUrl(it) }
            .distinct()
    }

    private fun extractIframeUrls(html: String, pageUrl: String): List<String> {
        val doc = Jsoup.parse(html.decodeEscaped(), pageUrl)
        return doc.select("iframe[src], iframe[data-src]").mapNotNull { resolveUrl(it.attr("src") ?: it.attr("data-src"), pageUrl) }.filterNot { isBadPlaybackUrl(it) }.distinct()
    }

    private fun extractPlot(document: Document): String? {
        document.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.cleanPlot()?.let { return it }
        val entry = document.selectFirst(".entry-content") ?: return null
        val clone = entry.clone()
        clone.select("script, style, h1, h2").remove()
        return clone.text().cleanPlot()
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.selectFirst("a[rel=next], .page-numbers.next") != null
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val cleanPath = path.trim('/')
        return if (cleanPath.isBlank() && page <= 1) mainUrl else "$mainUrl/$cleanPath/page/$page/"
    }

    private fun resolveUrl(raw: String?, base: String): String? {
        val clean = raw?.trim()?.decodeEscaped()?.takeIf { it.isNotBlank() && it != "#" } ?: return null
        if (clean.startsWith("javascript", true) || clean.startsWith("mailto:", true)) return null
        return runCatching {
            if (clean.startsWith("http", true)) normalizeProviderUrl(clean) else fixUrl(clean)
        }.getOrNull()
    }

    private fun normalizeProviderUrl(url: String): String {
        return url.replace("https://134.209.20.140", mainUrl).trim()
    }

    private fun isProviderUrl(url: String): Boolean {
        return url.startsWith(mainUrl)
    }

    private fun isBlockedUrl(url: String): Boolean = false

    private fun isBadPlaybackUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("facebook") || lower.contains("youtube") || lower.endsWith(".jpg") || lower.endsWith(".css")
    }

    private fun extractPosterUrl(element: Element, anchor: Element): String? {
        val image = element.selectFirst("img[data-src], img[src]") ?: return null
        val raw = image.getImageAttr() ?: return null
        return resolveUrl(raw, mainUrl)
    }

    private fun isBadImage(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("logo") || lower.endsWith(".svg")
    }

    private fun extractYear(text: String): Int? {
        return Regex("""\b(19\d{2}|20\d{2})\b""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun decodeBase64(value: String): String? = null // stub

    private fun String.slugTitle(): String {
        return substringAfterLast("/").replace("-", " ").cleanTitle().ifBlank { "BioskopKeren" }
    }

    private fun String.slugQuery(): String {
        return lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-')
    }

    private fun String.decodeEscaped(): String = this.replace("\\", "")

    private fun String.cleanTitle(): String = this.replace(Regex("\s+"), " ").trim()

    private fun String.cleanDetailTitle(): String = cleanTitle().replace(Regex("\s*(19|20)\d{2}\s*$"), "").trim()

    private fun String.cleanPlot(): String? = this.replace(Regex("\s+"), " ").trim().takeIf { it.isNotBlank() && it.length > 20 }

    private fun String.isUiText(): Boolean {
        val l = trim().lowercase()
        return l.isBlank() || l.length <= 1 || l.all { it.isDigit() } || l in setOf("home", "next", "movies", "genre")
    }

    private fun Element.getImageAttr(): String? {
        return attr("data-src").takeIf { it.isNotBlank() } ?: attr("src").takeIf { it.isNotBlank() }
    }
}
