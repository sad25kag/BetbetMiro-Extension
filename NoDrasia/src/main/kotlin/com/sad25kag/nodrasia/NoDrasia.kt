package com.sad25kag.nodrasia

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class NoDrasia : MainAPI() {
    override var mainUrl = "https://nodrasia.cc"
    override var name = "NoDrasia"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Latest Episodes",
        "$mainUrl/category/ongoing/page/%d/" to "Ongoing",
        "$mainUrl/category/drama-korea/page/%d/" to "Drama Korea",
        "$mainUrl/category/drama-china/page/%d/" to "Drama China",
        "$mainUrl/category/drama-taiwan/page/%d/" to "Drama Taiwan",
        "$mainUrl/category/drama-thailand/page/%d/" to "Drama Thailand",
        "$mainUrl/genre/comedy/page/%d/" to "Comedy",
        "$mainUrl/genre/fantasy/page/%d/" to "Fantasy",
        "$mainUrl/genre/historical/page/%d/" to "Historical",
        "$mainUrl/genre/youth/page/%d/" to "Youth"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.toPagedUrl(page)
        val document = app.get(url, headers = headers, referer = "$mainUrl/").document
        val results = document.toSearchResults()
        val hasNext = document.select("a[href*='/page/${page + 1}/'], a.next, .pagination a[href], .nav-links a[href]").isNotEmpty()
        return newHomePageResponse(request.name, results, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/page/1/?s=$encoded",
            "$mainUrl/search/$encoded/"
        )
        val results = linkedMapOf<String, SearchResponse>()
        for (url in urls) {
            val text = runCatching { app.get(url, headers = headers, referer = "$mainUrl/").text }.getOrNull().orEmpty()
            if (text.isBlank()) continue
            Jsoup.parse(text, mainUrl).toSearchResults().forEach { results[it.url] = it }
            if (results.isNotEmpty()) break
        }
        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = url.absoluteUrl(mainUrl) ?: url
        val document = app.get(pageUrl, headers = headers, referer = "$mainUrl/").document

        if (pageUrl.isEpisodeUrl()) {
            return document.toEpisodeLoadResponse(pageUrl)
        }

        val title = document.selectFirst("h1.entry-title, h1, .single-title, .sheader h1, meta[property=og:title], title")
            ?.textOrContent()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst(".poster img, .thumb img, .sheader img, .image img, .movie-poster img, meta[property=og:image], img.wp-post-image")
            ?.imageUrl(pageUrl)
        val plot = document.selectFirst(".entry-content p, .wp-content p, .synopsis, .desc, .summary, .storyline, meta[name=description], meta[property=og:description]")
            ?.textOrContent()
            ?.cleanText()
        val tags = document.select("a[href*='/genre/'], .genres a, .sgeneros a, .entry-categories a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val status = document.text().toShowStatus()
        val year = Regex("(?:19|20)\\d{2}").find(document.text())?.value?.toIntOrNull()

        val episodes = document.select(
            "a[href*='/nonton/'], a[href*='episode'], .eplister a[href], .episodios a[href], " +
                ".episodelist a[href], .episode-list a[href], .se-c a[href], .les-content a[href]"
        )
            .mapNotNull { it.toEpisode(pageUrl) }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name })

        val recommendations = document.select("article a[href], .ml-item a[href], .item a[href], .result-item a[href], .related a[href], .owl-item a[href]")
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == pageUrl }
            .distinctBy { it.url }

        val isMovie = pageUrl.contains("/movie", true) || title.contains("Movie", true)
        return if (episodes.isEmpty() || isMovie) {
            newMovieLoadResponse(title, pageUrl, if (isMovie) TvType.Movie else TvType.AsianDrama, pageUrl) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot?.let { this.plot = it }
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
            }
        } else {
            newTvSeriesLoadResponse(title, pageUrl, TvType.AsianDrama, episodes) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot?.let { this.plot = it }
                this.tags = tags
                this.year = year
                showStatus = status
                this.recommendations = recommendations
            }
        }
    }

    private suspend fun Document.toEpisodeLoadResponse(url: String): LoadResponse {
        val title = selectFirst("h1.entry-title, h1, .single-title, meta[property=og:title], title")
            ?.textOrContent()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast('/').replace('-', ' ').cleanTitle()
        val poster = selectFirst("meta[property=og:image], video[poster], .poster img, .thumb img, img.wp-post-image")?.imageUrl(url)
        val plot = selectFirst("meta[name=description], meta[property=og:description], .entry-content p")?.textOrContent()?.cleanText()
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            backgroundPosterUrl = poster
            plot?.let { this.plot = it }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data.absoluteUrl(mainUrl) ?: data
        val text = app.get(pageUrl, headers = headers, referer = "$mainUrl/").text
        val document = Jsoup.parse(text, pageUrl)
        val emitted = linkedSetOf<String>()
        var delivered = 0

        suspend fun emit(raw: String?, sourceName: String = name, refererUrl: String = pageUrl, depth: Int = 0) {
            val fixed = raw.decodeCandidate()?.absoluteUrl(refererUrl)?.cleanMediaUrl() ?: return
            if (fixed.isBlank() || !emitted.add(fixed)) return

            if (fixed.isDirectMedia()) {
                val type = if (fixed.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback(
                    newExtractorLink(sourceName, sourceName, fixed, type) {
                        quality = fixed.qualityFromUrl()
                        referer = refererUrl
                        headers = mapOf("Referer" to refererUrl, "User-Agent" to USER_AGENT)
                    }
                )
                delivered++
                return
            }

            if (fixed.startsWith("http", true)) {
                if (loadExtractor(fixed, refererUrl, subtitleCallback, callback)) {
                    delivered++
                    return
                }

                if (depth < 2 && fixed.isInspectableHost()) {
                    val hostText = runCatching { app.get(fixed, headers = headers, referer = refererUrl).text }.getOrNull().orEmpty()
                    if (hostText.isNotBlank()) {
                        val unpacked = runCatching { getAndUnpack(hostText) }.getOrNull().orEmpty()
                        val hostDocument = Jsoup.parse(hostText, fixed)
                        hostDocument.collectPlaybackCandidates().forEach { candidate ->
                            emit(candidate, fixed.hostLabel(), fixed, depth + 1)
                        }
                        MEDIA_URL_REGEX.findAll(hostText + "\n" + unpacked).forEach { match ->
                            emit(match.value, fixed.hostLabel(), fixed, depth + 1)
                        }
                        IFRAME_REGEX.findAll(hostText + "\n" + unpacked).forEach { match ->
                            emit(match.groupValues[1], fixed.hostLabel(), fixed, depth + 1)
                        }
                    }
                }
            }
        }

        document.collectPlaybackCandidates().forEach { emit(it) }

        document.select(".dooplay_player_option, .options li, li[data-post][data-nume][data-type]").forEach { option ->
            resolveDooplayOption(option, pageUrl).forEach { emit(it, name, pageUrl) }
        }

        Regex("""(?:atob|Base64\.decode)\(['\"]([^'\"]+)['\"]\)""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .forEach { emit(runCatching { base64Decode(it.groupValues[1]) }.getOrNull()) }

        val unpacked = runCatching { getAndUnpack(text) }.getOrNull().orEmpty()
        MEDIA_URL_REGEX.findAll(text + "\n" + unpacked).forEach { match -> emit(match.value) }
        IFRAME_REGEX.findAll(text + "\n" + unpacked).forEach { match -> emit(match.groupValues[1]) }
        PLAYER_URL_REGEX.findAll(text + "\n" + unpacked).forEach { match -> emit(match.groupValues[1]) }

        return delivered > 0
    }

    private suspend fun resolveDooplayOption(option: Element, refererUrl: String): List<String> {
        val post = option.attr("data-post").ifBlank { option.attr("data-id") }
        val nume = option.attr("data-nume").ifBlank { option.attr("data-server") }.ifBlank { option.attr("data-n") }
        val type = option.attr("data-type").ifBlank { "tv" }
        if (post.isBlank() || nume.isBlank()) return emptyList()

        val ajaxUrls = listOf(
            "$mainUrl/wp-admin/admin-ajax.php",
            "$mainUrl/wp-json/dooplay/v1/post/$post?type=$type&nume=$nume"
        )
        val results = mutableListOf<String>()
        val body = mapOf("action" to "doo_player_ajax", "post" to post, "nume" to nume, "type" to type)
        for (ajaxUrl in ajaxUrls) {
            val response = runCatching {
                if (ajaxUrl.contains("admin-ajax")) app.post(ajaxUrl, data = body, headers = headers, referer = refererUrl).text
                else app.get(ajaxUrl, headers = headers, referer = refererUrl).text
            }.getOrNull().orEmpty()
            if (response.isBlank()) continue
            IFRAME_REGEX.findAll(response).forEach { results.add(it.groupValues[1]) }
            PLAYER_URL_REGEX.findAll(response).forEach { results.add(it.groupValues[1]) }
            MEDIA_URL_REGEX.findAll(response).forEach { results.add(it.value) }
            Regex("""["']embed_url["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(response).forEach { results.add(it.groupValues[1]) }
            Regex("""["']url["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(response).forEach { results.add(it.groupValues[1]) }
        }
        return results
    }

    private fun Document.collectPlaybackCandidates(): List<String> {
        val results = mutableListOf<String>()
        select("iframe[src], embed[src], video[src], video source[src], source[src]").forEach { element ->
            results.add(element.attr("src"))
        }
        select("[data-src], [data-url], [data-link], [data-href], [data-iframe], [data-embed], [data-player], [data-video], [data-file], [data-stream], option[value], a[href]").forEach { element ->
            listOf(
                element.attr("data-src"),
                element.attr("data-url"),
                element.attr("data-link"),
                element.attr("data-href"),
                element.attr("data-iframe"),
                element.attr("data-embed"),
                element.attr("data-player"),
                element.attr("data-video"),
                element.attr("data-file"),
                element.attr("data-stream"),
                element.attr("value"),
                element.attr("href")
            ).filterTo(results) { it.isNotBlank() }
        }
        return results
    }

    private fun Document.toSearchResults(): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        select(
            "article a[href], .ml-item a[href], .item a[href], .result-item a[href], .poster a[href], " +
                ".movies-list a[href], .module a[href], .content a[href], .latest a[href], .archive a[href]"
        ).forEach { element ->
            element.toSearchResult()?.let { results[it.url] = it }
        }
        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (tagName().equals("a", true) && hasAttr("href")) this else selectFirst("a[href]") ?: return null
        val href = anchor.attr("href").absoluteUrl(mainUrl) ?: return null
        if (!href.startsWith(mainUrl)) return null
        if (!href.isContentUrl()) return null

        val scope = when {
            hasClass("ml-item") || hasClass("item") || hasClass("result-item") || hasClass("poster") || tagName().equals("article", true) -> this
            else -> anchor.parent() ?: anchor
        }
        val title = listOf(
            scope.selectFirst("h1, h2, h3, h4, .entry-title, .post-title, .mli-title, .title, .name")?.text(),
            anchor.attr("title"),
            scope.selectFirst("img[alt]")?.attr("alt"),
            anchor.text()
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?.takeIf { it.length > 2 }
            ?: return null

        val poster = scope.selectFirst("img")?.imageUrl(href)
        return if (href.isEpisodeUrl() || title.contains("Episode", true) || title.matches(Regex("(?i).*\\bEP\\d+\\b.*"))) {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { posterUrl = poster }
        }
    }

    private fun Element.toEpisode(baseUrl: String): Episode? {
        val href = attr("href").absoluteUrl(baseUrl) ?: return null
        if (!href.contains("/nonton/", true) && !href.contains("episode", true)) return null
        val text = text().trim()
        val epNum = Regex("(?i)(?:EP|Episode|Eps?\\.?|Ep\\.?)\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?i)(?:episode|ep)[-_ ]?(\\d+)").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return newEpisode(href) {
            name = text.cleanTitle().ifBlank { "Episode ${epNum ?: ""}" }
            episode = epNum
            posterUrl = selectFirst("img")?.imageUrl(baseUrl)
        }
    }

    private fun String.toPagedUrl(page: Int): String {
        return if (page <= 1) {
            replace("/page/%d/", "/").replace("%d", "")
        } else {
            format(page)
        }
    }

    private fun Element.textOrContent(): String {
        return attr("content").ifBlank { text() }.trim()
    }

    private fun Element.imageUrl(baseUrl: String): String? {
        val raw = attr("content")
            .ifBlank { attr("src") }
            .ifBlank { attr("data-src") }
            .ifBlank { attr("data-lazy-src") }
            .ifBlank { attr("data-original") }
            .ifBlank { attr("poster") }
        return raw.absoluteUrl(baseUrl)
    }

    private fun String?.absoluteUrl(baseUrl: String): String? {
        val raw = this?.trim()?.replace("&amp;", "&")?.replace("\\/", "/") ?: return null
        if (raw.isBlank() || raw == "#" || raw.startsWith("javascript", true) || raw.startsWith("mailto:", true)) return null
        val fixed = if (raw.startsWith("//")) "https:$raw" else raw
        return runCatching { URI(baseUrl).resolve(fixed).toString() }.getOrNull()
    }

    private fun String?.decodeCandidate(): String? {
        val raw = this?.trim()?.trim(' ', '\'', '"') ?: return null
        if (raw.isBlank()) return null
        val clean = raw.htmlUnescape().replace("\\/", "/")
        if (clean.startsWith("http", true) || clean.startsWith("//") || clean.startsWith("/") || clean.startsWith("./")) return clean
        if (clean.length > 16 && clean.matches(Regex("^[A-Za-z0-9+/=_-]+$"))) {
            runCatching { base64Decode(clean) }.getOrNull()?.let { decoded ->
                if (decoded.contains("http", true) || decoded.contains("iframe", true)) return decoded
            }
        }
        return clean
    }

    private fun String.cleanTitle(): String {
        return htmlUnescape()
            .replace("NODRASIA", "", ignoreCase = true)
            .replace("Nodrasia", "", ignoreCase = true)
            .replace("Nonton Drama BL/GL Asia", "", ignoreCase = true)
            .replace("Nonton Drama BL/GL Thailand", "", ignoreCase = true)
            .replace("Nonton Drama BL Korea Subtitle Indonesia", "", ignoreCase = true)
            .replace("Sub Indo", "", ignoreCase = true)
            .replace("Subtitle Indonesia", "", ignoreCase = true)
            .replace(Regex("(?i)\\s*[-|].*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', ':')
    }

    private fun String.cleanText(): String {
        return Jsoup.parse(this).text().replace(Regex("\\s+"), " ").trim()
    }

    private fun String.cleanMediaUrl(): String {
        return replace("\\/", "/")
            .replace("&amp;", "&")
            .let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            .trim(' ', '\'', '"')
    }

    private fun String.htmlUnescape(): String {
        return replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#8217;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun String.isContentUrl(): Boolean {
        if (!startsWith(mainUrl)) return false
        if (contains("/category/", true) || contains("/genre/", true) || contains("/country/", true)) return false
        if (contains("/tag/", true) || contains("/page/", true) || contains("/advanced-search", true)) return false
        return contains("/series/", true) || contains("/nonton/", true)
    }

    private fun String.isEpisodeUrl(): Boolean {
        return contains("/nonton/", true) || contains("episode", true)
    }

    private fun String.toShowStatus(): ShowStatus? {
        val lower = lowercase()
        return when {
            lower.contains("completed") || lower.contains("tamat") || lower.contains("end") -> ShowStatus.Completed
            lower.contains("ongoing") || lower.contains("on-going") -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun String.isDirectMedia(): Boolean {
        val lower = lowercase()
        return lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains(".webm") ||
            lower.contains(".mkv") || lower.contains("videoplayback")
    }

    private fun String.qualityFromUrl(): Int {
        val lower = lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value
            lower.contains("1080") -> Qualities.P1080.value
            lower.contains("720") -> Qualities.P720.value
            lower.contains("480") -> Qualities.P480.value
            lower.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String.isInspectableHost(): Boolean {
        val lower = lowercase()
        return startsWith("http", true) && !lower.contains("nodrasia.cc") && !isDirectMedia()
    }

    private fun String.hostLabel(): String {
        return runCatching { URI(this).host?.removePrefix("www.")?.substringBefore('.')?.replaceFirstChar { it.uppercase() } }
            .getOrNull()
            ?: name
    }

    companion object {
        private val MEDIA_URL_REGEX = Regex("""https?://[^'"<>()\s]+?(?:\.mp4|\.m3u8|\.webm|\.mkv|videoplayback)[^'"<>()\s]*""", RegexOption.IGNORE_CASE)
        private val IFRAME_REGEX = Regex("""<(?:iframe|embed)[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val PLAYER_URL_REGEX = Regex("""(?:file|source|src|url|iframe|embed)\s*[:=]\s*["']([^"']+)""", RegexOption.IGNORE_CASE)
    }
}
