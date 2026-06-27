package com.sad25kag.doronime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class Doronime : MainAPI() {
    override var mainUrl = "https://doronime.id"
    override var name = "Doronime"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl/?page={page}" to "Anime Terbaru",
        "$mainUrl/movie?page={page}" to "Movie",
        "$mainUrl/batch?page={page}" to "Batch",
        "$mainUrl/genre/adventure?page={page}" to "Adventure",
        "$mainUrl/genre/comedy?page={page}" to "Comedy",
        "$mainUrl/genre/romance?page={page}" to "Romance"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.replace("{page}", page.toString())
        val document = app.get(url, headers = defaultHeaders, referer = mainUrl).document
        val cards = parseListingCards(document, url)
            .filter { it.posterUrl?.isNotBlank() == true }
            .distinctBy { it.url }
        val hasNext = document.select("a[href*='?page=${page + 1}'], a[href$='page=${page + 1}'], a:matchesOwn((?i)next)").isNotEmpty()

        return newHomePageResponse(
            HomePageList(request.name, cards, isHorizontalImages = false),
            hasNext = hasNext || cards.isNotEmpty()
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = query.urlEncoded()
        val candidates = listOf(
            "$mainUrl/search?keyword=$encoded",
            "$mainUrl/?s=$encoded",
            "$mainUrl/anime?search=$encoded",
            "$mainUrl/anime?keyword=$encoded",
            "$mainUrl/anime?view=list"
        )

        val results = linkedMapOf<String, SearchResponse>()
        for (url in candidates) {
            val document = runCatching {
                app.get(url, headers = defaultHeaders, referer = mainUrl).document
            }.getOrNull() ?: continue

            parseListingCards(document, url)
                .filter { item -> item.title.contains(query, ignoreCase = true) }
                .forEach { item -> results.putIfAbsent(item.url, item) }

            if (results.isNotEmpty()) break
        }

        return results.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders, referer = mainUrl).document
        val title = document.bestTitle(url)
            ?.cleanDetailTitle()
            ?: throw ErrorLoadingException("Judul Doronime tidak ditemukan")
        val poster = document.bestPoster(url)
        val tags = document.detailGenres()
        val recommendations = document.select("article, .post, .item, .bs, .series, .listupd .bs, main a[href*='/anime/']")
            .mapNotNull { it.toSearchResult(url) }
            .distinctBy { it.url }
            .filterNot { it.url == url }
            .take(20)

        val playablePage = url.isDoronimePlayableUrl()
        val parsedEpisodes = if (playablePage) emptyList() else parseEpisodes(document, url)
        val fallbackPlayable = findFirstPlayableUrl(document, url) ?: url.takeIf { playablePage }
        val episodes = parsedEpisodes.ifEmpty {
            fallbackPlayable?.let { playableUrl ->
                listOf(
                    newEpisode(playableUrl) {
                        this.name = title
                        this.episode = playableUrl.episodeNumber() ?: 1
                        this.posterUrl = poster
                    }
                )
            } ?: emptyList()
        }
        val plot = document.detailSynopsis()

        return newAnimeLoadResponse(
            title.removeEpisodeSuffix(),
            url,
            TvType.Anime
        ) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = defaultHeaders, referer = mainUrl)
        val document = response.document
        val seen = linkedSetOf<String>()
        var emitted = false
        val trackedCallback: (ExtractorLink) -> Unit = { link ->
            emitted = true
            callback(link)
        }

        suspend fun emitDirect(rawUrl: String, label: String) {
            val finalUrl = rawUrl.decodePlayerText().toAbsoluteUrl(data).takeIf { it.isValidMediaUrl() } ?: return
            val key = finalUrl.substringBefore("#")
            if (!seen.add(key)) return

            if (finalUrl.contains(".m3u8", true)) {
                M3u8Helper.generateM3u8(label.ifBlank { name }, finalUrl, data).forEach(trackedCallback)
            } else {
                trackedCallback(
                    newExtractorLink(
                        source = name,
                        name = label.ifBlank { name },
                        url = finalUrl,
                        type = inferType(finalUrl)
                    ) {
                        this.referer = data
                        this.quality = getQualityFromName(label.ifBlank { finalUrl })
                        this.headers = mapOf(
                            "Referer" to data,
                            "Origin" to mainUrl,
                            "User-Agent" to USER_AGENT
                        )
                    }
                )
            }
        }

        suspend fun resolveCandidate(rawValue: String, referer: String, label: String) {
            decodeServerValue(rawValue).forEach { decoded ->
                extractPlayerUrls(decoded, referer).forEach { playerUrl ->
                    val finalUrl = playerUrl.decodePlayerText().toAbsoluteUrl(referer)
                    if (finalUrl.isBlank()) return@forEach

                    when {
                        finalUrl.isValidMediaUrl() -> emitDirect(finalUrl, label)
                        finalUrl.startsWith("http", true) && seen.add(finalUrl.substringBefore("#")) -> {
                            runCatching {
                                loadExtractor(finalUrl, referer, subtitleCallback, trackedCallback)
                            }
                        }
                    }
                }
            }
        }

        document.select("a[href*='/download'], a[href*='doronime.id/download']")
            .forEach { element ->
                val label = element.text().cleanText()
                if (!label.isDoronimeDownloadHost()) return@forEach

                val downloadUrl = element.attr("href").toAbsoluteUrl(data).substringBefore("#")
                if (!downloadUrl.startsWith("$mainUrl/download", true) || !seen.add(downloadUrl)) return@forEach

                runCatching {
                    DoronimeDownload().getUrl(downloadUrl, data, subtitleCallback, trackedCallback)
                }
            }

        document.select(
            "select option[value], .mobius option[value], .mirror option[value], " +
                ".server option[value], .player option[value], [data-embed], [data-src], [data-url], [data-video], [data-player], [data-iframe], [data-value]"
        ).forEach { element ->
            val rawValue = listOf(
                "value",
                "data-embed",
                "data-src",
                "data-url",
                "data-video",
                "data-player",
                "data-iframe",
                "data-value"
            ).firstNotNullOfOrNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }

            if (!rawValue.isNullOrBlank()) {
                val label = element.text().cleanText().ifBlank { element.attr("data-name").cleanText() }.ifBlank { name }
                resolveCandidate(rawValue, data, label)
            }
        }

        document.select("iframe[src], embed[src], video[src], source[src], a[href*='embed'], a[href*='player'], a[href*='.m3u8'], a[href*='.mp4']")
            .forEach { element ->
                val rawUrl = element.attr("src").ifBlank { element.attr("href") }
                if (rawUrl.isNotBlank()) resolveCandidate(rawUrl, data, element.text().cleanText().ifBlank { name })
            }

        extractPlayerUrls(response.text.decodePlayerText(), data).forEach { playerUrl ->
            resolveCandidate(playerUrl, data, name)
        }

        return emitted
    }

    private fun parseListingCards(document: Document, pageUrl: String): List<SearchResponse> {
        val structured = document.select(
            "article, .post, .post-item, .item, .bs, .listupd .bs, .eplister li, .episodelist li, .series, .animepost, .venz li, .ml-item"
        ).mapNotNull { it.toSearchResult(pageUrl) }

        if (structured.isNotEmpty()) return structured

        return document.select("main a[href*='/anime/'], body a[href*='/anime/']")
            .mapNotNull { it.toSearchResult(pageUrl) }
    }

    private fun Element.toSearchResult(pageUrl: String): SearchResponse? {
        val anchor = if (tagName().equals("a", true)) this else selectFirst("a[href*='/anime/']")
        val href = anchor?.attr("href")?.toAbsoluteUrl(pageUrl)?.substringBefore("#") ?: return null
        if (!href.isDoronimeItemUrl()) return null

        val title = anchor.attr("title").cleanText()
            .ifBlank { selectFirst("h1, h2, h3, h4, .title, .tt, .entry-title, .post-title")?.text()?.cleanText().orEmpty() }
            .ifBlank { anchor.text().cleanText() }
            .ifBlank { selectFirst("img[alt]")?.attr("alt")?.cleanText().orEmpty() }
            .removeNoisePrefix()

        if (title.length < 3 || title.isNavigationNoise()) return null

        val poster = findPoster(this, pageUrl) ?: findPoster(anchor, pageUrl)
        val type = when {
            href.contains("/movie", true) || title.contains("movie", true) -> TvType.AnimeMovie
            title.contains("ova", true) || title.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    private fun parseEpisodes(document: Document, pageUrl: String): List<Episode> {
        val seriesSlug = pageUrl.substringAfter("/anime/", "").substringBefore("/")
        val parsed = document.select("a[href*='/episode-'], a[href$='/movie'], a[href*='/movie']")
            .mapNotNull { anchor ->
                val href = anchor.attr("href").toAbsoluteUrl(pageUrl).substringBefore("#")
                if (!href.isDoronimePlayableUrl()) return@mapNotNull null
                if (seriesSlug.isNotBlank() && !href.contains("/anime/$seriesSlug/")) return@mapNotNull null

                val rawName = anchor.attr("title").cleanText()
                    .ifBlank { anchor.text().cleanText() }
                    .ifBlank { href.substringAfterLast('/').replace('-', ' ').cleanText() }

                val number = href.episodeNumber() ?: rawName.episodeNumber()
                ParsedEpisode(
                    episode = newEpisode(href) {
                        this.name = rawName.ifBlank { number?.let { "Episode $it" } ?: "Episode" }
                        this.episode = number
                        this.posterUrl = findPoster(anchor, pageUrl)
                    },
                    number = number
                )
            }
            .distinctBy { it.episode.data }

        return parsed.sortedWith(compareBy<ParsedEpisode> { it.number ?: Int.MAX_VALUE }.thenBy { it.episode.name }).map { it.episode }
    }

    private fun findFirstPlayableUrl(document: Document, pageUrl: String): String? {
        if (pageUrl.isDoronimePlayableUrl()) return pageUrl
        val seriesSlug = pageUrl.substringAfter("/anime/", "").substringBefore("/")
        return document.select("a[href*='/episode-'], a[href$='/movie'], a[href*='/movie']")
            .mapNotNull { it.attr("href").toAbsoluteUrl(pageUrl).substringBefore("#").takeIf { href -> href.isDoronimePlayableUrl() } }
            .firstOrNull { href -> seriesSlug.isBlank() || href.contains("/anime/$seriesSlug/") }
    }

    private fun decodeServerValue(rawValue: String): List<String> {
        val source = rawValue.decodePlayerText().trim()
        if (source.isBlank()) return emptyList()

        val decoded = linkedSetOf(source)
        runCatching { URLDecoder.decode(source, "UTF-8") }
            .getOrNull()
            ?.decodePlayerText()
            ?.takeIf { it.isNotBlank() }
            ?.let { decoded.add(it) }

        Regex("""atob\((['"])(.*?)\1\)""", RegexOption.IGNORE_CASE)
            .findAll(source)
            .mapNotNull { match -> runCatching { base64Decode(match.groupValues[2]) }.getOrNull() }
            .forEach { decoded.add(it.decodePlayerText()) }

        val base64Candidate = source.substringBefore("?").trim()
        if (base64Candidate.length > 20 && base64Candidate.matches(Regex("^[A-Za-z0-9+/=_-]+$"))) {
            runCatching { base64Decode(base64Candidate.replace('-', '+').replace('_', '/')) }
                .getOrNull()
                ?.decodePlayerText()
                ?.takeIf { it.isNotBlank() }
                ?.let { decoded.add(it) }
        }

        return decoded.toList()
    }

    private fun extractPlayerUrls(text: String, baseUrl: String): List<String> {
        val candidates = linkedSetOf<String>()
        val parsed = Jsoup.parse(text, baseUrl)

        parsed.select("iframe[src], embed[src], video[src], source[src], a[href]").forEach { element ->
            val rawUrl = element.attr("src").ifBlank { element.attr("href") }
            if (rawUrl.isNotBlank()) candidates.add(rawUrl)
        }

        val patterns = listOf(
            Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|source|src|url)\s*[:=]\s*["']([^"']+(?:\.m3u8|\.mp4|\.mpd|/embed/|/videoembed/|/player/)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](https?:\\?/\\?/[^"']+(?:\.m3u8|\.mp4|\.mpd|/embed/|/videoembed/|/player/)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](//[^"']+(?:\.m3u8|\.mp4|\.mpd|/embed/|/videoembed/|/player/)[^"']*)["']""", RegexOption.IGNORE_CASE)
        )

        patterns.forEach { pattern ->
            pattern.findAll(text).forEach { match ->
                match.groupValues.getOrNull(1)?.decodePlayerText()?.takeIf { it.isNotBlank() }?.let(candidates::add)
            }
        }

        if (text.trim().startsWith("http", true) || text.trim().startsWith("//")) {
            candidates.add(text.trim())
        }

        return candidates.map { it.toAbsoluteUrl(baseUrl) }
            .filter { it.isPlayableCandidateUrl() }
            .distinct()
    }

    private fun findPoster(element: Element, pageUrl: String): String? {
        val boxes = listOfNotNull(
            element,
            element.parent(),
            element.parent()?.parent(),
            element.parent()?.parent()?.parent()
        ).distinct()

        for (box in boxes) {
            extractImage(box, pageUrl)?.let { return it }
            box.select("img, source, div, span, a").forEach { child ->
                extractImage(child, pageUrl)?.let { return it }
            }
        }

        return null
    }

    private fun Document.bestPoster(pageUrl: String): String? {
        selectFirst("meta[property=og:image], meta[name=twitter:image]")
            ?.attr("content")
            ?.toAbsoluteUrl(pageUrl)
            ?.takeIf { it.isImageCandidate() }
            ?.let { return it }

        return findPoster(body(), pageUrl)
    }

    private fun Document.bestTitle(pageUrl: String): String? {
        selectFirst("h1.entry-title, h1.post-title, h1.title, h1")
            ?.text()
            ?.cleanText()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        selectFirst("meta[property=og:title], meta[name=twitter:title]")
            ?.attr("content")
            ?.cleanText()
            ?.removeSuffix(" - Doronime")
            ?.removeSuffix(" | Doronime")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return pageUrl.substringAfterLast('/').replace('-', ' ').cleanText().takeIf { it.isNotBlank() }
    }

    private fun Document.detailGenres(): List<String> {
        return select("li, p, tr, div")
            .filter { Regex("""(?i)\bGenre\s*:""").containsMatchIn(it.text()) }
            .sortedBy { it.text().length }
            .firstNotNullOfOrNull { row ->
                val links = row.select("a[href*='/genre/']")
                    .map { it.text().cleanText() }
                    .filter { it.isNotBlank() }
                    .distinct()
                if (links.isNotEmpty()) {
                    links
                } else {
                    row.text()
                        .substringAfter(":", "")
                        .split(',')
                        .map { it.cleanText() }
                        .filter { it.isNotBlank() }
                        .takeIf { it.isNotEmpty() }
                }
            }
            .orEmpty()
    }

    private fun Document.detailSynopsis(): String? {
        select(".sinopsis, .synopsis, #sinopsis").forEach { element ->
            element.text()
                .cleanSynopsisText()
                .takeIf { it.length > 40 }
                ?.let { return it }
        }

        val marker = select("h1, h2, h3, h4, h5, h6, .card-header, .panel-heading, .box-header, .heading, .title, div, span")
            .firstOrNull { it.ownText().cleanText().equals("Sinopsis", ignoreCase = true) }

        val candidates = listOfNotNull(
            marker?.nextElementSibling(),
            marker?.parent()?.selectFirst(".card-body, .panel-body, .box-body, .entry-content, .content"),
            marker?.parent()?.nextElementSibling()
        ).distinct()

        for (candidate in candidates) {
            candidate.text()
                .cleanSynopsisText()
                .takeIf { it.length > 40 }
                ?.let { return it }
        }

        return null
    }

    private fun extractImage(element: Element, pageUrl: String): String? {
        listOf("data-src", "data-lazy-src", "data-original", "data-image", "data-img", "src", "poster").forEach { attr ->
            val value = element.attr(attr).trim()
            if (value.isImageCandidate()) return value.toAbsoluteUrl(pageUrl)
        }

        listOf("srcset", "data-srcset").forEach { attr ->
            element.attr(attr)
                .split(',')
                .map { it.trim().substringBefore(' ').trim() }
                .firstOrNull { it.isImageCandidate() }
                ?.let { return it.toAbsoluteUrl(pageUrl) }
        }

        Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
            .find(element.attr("style"))
            ?.groupValues
            ?.getOrNull(2)
            ?.takeIf { it.isImageCandidate() }
            ?.let { return it.toAbsoluteUrl(pageUrl) }

        return null
    }

    private fun String.toAbsoluteUrl(baseUrl: String): String {
        val value = decodePlayerText().trim()
        return when {
            value.isBlank() -> ""
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> {
                val base = baseUrl.substringBeforeLast('/', mainUrl)
                "$base/$value"
            }
        }
    }

    private fun String.decodePlayerText(): String {
        return replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("%2F", "/")
            .replace("%3A", ":")
    }

    private fun String.cleanText(): String {
        return replace(Regex("\\s+"), " ").trim()
    }

    private fun String.cleanDetailTitle(): String {
        return cleanText()
            .replace(Regex("""(?i)^anime\s+"""), "")
            .replace(Regex("""(?i)\s+-\s*Doronime$"""), "")
            .replace(Regex("""(?i)\s+\|\s*Doronime$"""), "")
            .cleanText()
    }

    private fun String.cleanSynopsisText(): String {
        return cleanText()
            .replace(Regex("""(?i)^sinopsis\s*[:\-]?\s*"""), "")
            .replace(Regex("""(?i)\s*pasang\s+iklan.*$"""), "")
            .cleanText()
    }

    private fun String.urlEncoded(): String = runCatching {
        URLEncoder.encode(this, "UTF-8")
    }.getOrDefault(this)

    private fun String.isDoronimeItemUrl(): Boolean {
        if (!startsWith(mainUrl, true)) return false
        if (!contains("/anime/", true)) return false
        if (contains("?view=", true) || contains("/genre/", true)) return false
        return true
    }

    private fun String.isDoronimePlayableUrl(): Boolean {
        return isDoronimeItemUrl() && (contains("/episode-", true) || endsWith("/movie", true) || contains("/movie?", true))
    }

    private fun String.isDoronimeDownloadHost(): Boolean {
        val value = cleanText().lowercase()
        return value == "gd" || value == "gdrive" || value.contains("google drive") || value == "af" || value == "acefile"
    }

    private fun String.isPlayableCandidateUrl(): Boolean {
        if (isBlank()) return false
        if (startsWith("#")) return false
        return startsWith("http", true) || startsWith("//") || startsWith("/") || contains(".m3u8", true) || contains(".mp4", true)
    }

    private fun String.isValidMediaUrl(): Boolean {
        return contains(".m3u8", true) || contains(".mp4", true) || contains(".mpd", true)
    }

    private fun String.isImageCandidate(): Boolean {
        if (isBlank()) return false
        if (startsWith("data:", true)) return false
        if (contains("blank", true) || contains("placeholder", true) || contains("spacer", true)) return false
        return contains(".jpg", true) ||
            contains(".jpeg", true) ||
            contains(".png", true) ||
            contains(".webp", true) ||
            contains("/wp-content/uploads/", true) ||
            contains("/images/", true)
    }

    private fun String.episodeNumber(): Int? {
        Regex("""(?i)(?:episode|eps|ep)[\s\-.]*(\d{1,4})""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("""/episode-(\d{1,4})""", RegexOption.IGNORE_CASE).find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private fun String.removeEpisodeSuffix(): String {
        return replace(Regex("""(?i)\s+episode\s+\d{1,4}.*$"""), "").cleanText()
    }

    private fun String.removeNoisePrefix(): String {
        return replace(Regex("""(?i)^(completed|ongoing|delay|not aired yet|tv|bd|ova|movie|special|batch|end)\s+"""), "")
            .replace(Regex("""\b\d\.\d{1,2}\b"""), "")
            .cleanText()
    }

    private fun String.isNavigationNoise(): Boolean {
        val value = lowercase()
        return value in setOf("home", "genre", "season", "movie", "batch", "ost", "next", "previous", "daftar anime")
    }

    private fun inferType(url: String): ExtractorLinkType {
        return when {
            url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            url.contains(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
    }

    private data class ParsedEpisode(val episode: Episode, val number: Int?)

    private val defaultHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )
}
