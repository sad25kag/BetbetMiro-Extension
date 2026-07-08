package com.sad25kag.animemovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeMovies : MainAPI() {
    override var mainUrl = "https://animemovies.org"
    override var name = "AnimeMovies"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Referer" to "$mainUrl/"
    )

    private fun rscHeaders(referer: String): Map<String, String> = siteHeaders + mapOf(
        "Accept" to "text/x-component,*/*;q=0.8",
        "RSC" to "1",
        "Next-Url" to referer.removePrefix(mainUrl).ifBlank { "/" }
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Episode Terbaru",
        "$mainUrl/anime?status=Ongoing" to "Anime Ongoing",
        "$mainUrl/anime?status=Completed" to "Anime Completed",
        "$mainUrl/anime?type=Movie" to "Anime Movie",
        "$mainUrl/anime?sort=popular" to "Anime Populer"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(buildPageUrl(request.data, page), headers = siteHeaders).document
        val items = document.parseAnimeItems(preferMovie = request.name.contains("Movie", true))
        return newHomePageResponse(request.name, items, hasNext = document.hasNextPage(page))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        val encoded = withContext(Dispatchers.IO) {
            URLEncoder.encode(cleanQuery, "UTF-8")
        }
        if (encoded.isBlank()) return emptyList()

        val tokens = cleanQuery.searchTokens()
        if (tokens.isEmpty()) return emptyList()

        val results = linkedMapOf<String, SearchResponse>()
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/anime?search=$encoded",
            "$mainUrl/anime?q=$encoded",
            "$mainUrl/search?q=$encoded"
        )

        for (url in urls) {
            val document = runCatching { app.get(url, headers = siteHeaders).document }.getOrNull() ?: continue
            val matches = document.parseAnimeItems()
                .filter { it.name.matchesSearchTokens(tokens, it.url) }
            for (item in matches) {
                results[item.url] = item
            }
            if (results.isNotEmpty()) break
        }

        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = fixUrl(url)
        val document = app.get(fixedUrl, headers = siteHeaders).document
        val isWatchPage = fixedUrl.contains("/watch/", true)
        val sourceTexts = mutableListOf(document.html())

        for (rscUrl in fixedUrl.rscUrls()) {
            runCatching { app.get(rscUrl, headers = rscHeaders(fixedUrl), referer = fixedUrl).text }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { sourceTexts.add(it) }
        }

        val mergedText = sourceTexts.joinToString("\n")
        val title = document.bestTitle()
            ?.cleanTitle()
            ?.ifBlank { null }
            ?: fixedUrl.slugTitle()

        val poster = document.bestPoster()
        val plot = document.bestPlot()
        val year = document.text().parseYear()
        val tags = document.select("a[href*=/genre/], a[href*='/genre/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length <= 40 }
            .distinct()
            .take(20)
        val status = mergedText.toShowStatus()
        val recommendations = document.parseAnimeItems().filter { it.url != fixedUrl }.take(24)

        val episodeLinks = linkedMapOf<String, Episode>()
        document.parseEpisodeLinks().forEach { episodeLinks[it.data] = it }
        mergedText.extractWatchUrls().mapNotNull { it.toEpisodeFromUrl() }.forEach { episodeLinks[it.data] = it }

        val episodeRange = title.parseEpisodeRange() ?: mergedText.parseEpisodeRange()
        val generatedEpisodes = if (episodeLinks.isEmpty() && episodeRange != null && !isWatchPage) {
            generateEpisodeRange(fixedUrl, title, poster, episodeRange)
        } else {
            emptyList()
        }

        val episodes = (episodeLinks.values.toList() + generatedEpisodes)
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.episode ?: Int.MAX_VALUE }, { it.name ?: "" }))

        val isSeriesBundle = episodeRange != null && !title.looksLikeMovie()
        val type = when {
            title.contains("OVA", true) || title.contains("Special", true) -> TvType.OVA
            title.looksLikeMovie() || fixedUrl.contains("type=Movie", true) -> TvType.AnimeMovie
            isSeriesBundle || episodes.size > 1 -> TvType.Anime
            else -> TvType.Anime
        }

        return if (episodes.isNotEmpty() && type != TvType.AnimeMovie) {
            newAnimeLoadResponse(title.cleanSeriesTitle(), fixedUrl, type) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.showStatus = status
                this.recommendations = recommendations
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else if (isWatchPage) {
            newMovieLoadResponse(title, fixedUrl, type, fixedUrl) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            val data = episodes.firstOrNull()?.data ?: fixedUrl
            newMovieLoadResponse(title.cleanSeriesTitle(), fixedUrl, type, data) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data.substringBefore("#").takeIf { it.startsWith("http", true) } ?: return false
        val requestedEpisode = Regex("""[?#&]episode=(\d{1,4})""", RegexOption.IGNORE_CASE)
            .find(data)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val queue = ArrayDeque<ServerCandidate>()
        val visited = linkedSetOf<String>()
        val emitted = linkedSetOf<String>()
        var hasLinks = false

        suspend fun enqueueFromText(text: String?, referer: String) {
            text?.extractServerCandidatesFromText(referer)?.forEach { queue.add(it) }
        }

        suspend fun enqueueFromUrl(url: String, referer: String, headers: Map<String, String> = siteHeaders) {
            val response = runCatching { app.get(url, headers = headers, referer = referer) }.getOrNull() ?: return
            response.document.extractServerCandidates(url).forEach { queue.add(it) }
            enqueueFromText(response.text, url)
        }

        suspend fun enqueueEpisodePageFromSeries(seriesUrl: String, episode: Int) {
            val response = runCatching { app.get(seriesUrl, headers = siteHeaders, referer = "$mainUrl/") }.getOrNull()
            val seriesTitle = response?.document?.bestTitle()?.cleanSeriesTitle()?.ifBlank { null } ?: seriesUrl.slugTitle().cleanSeriesTitle()
            val texts = mutableListOf<String>()
            response?.text?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
            for (rscUrl in seriesUrl.rscUrls()) {
                runCatching { app.get(rscUrl, headers = rscHeaders(seriesUrl), referer = seriesUrl).text }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { texts.add(it) }
            }

            val discovered = texts
                .asSequence()
                .flatMap { it.extractWatchUrls().asSequence() }
                .distinct()
                .filter { it.parseEpisodeNumber() == episode }
                .toList()

            for (watchUrl in discovered) {
                enqueueFromUrl(watchUrl, seriesUrl)
            }

            if (discovered.isEmpty()) {
                for (guess in seriesUrl.guessWatchUrls(seriesTitle, episode)) {
                    enqueueFromUrl(guess, seriesUrl)
                }
            }
        }

        if (requestedEpisode != null && pageUrl.contains("/anime/", true)) {
            enqueueEpisodePageFromSeries(pageUrl, requestedEpisode)
        } else {
            enqueueFromUrl(pageUrl, "$mainUrl/")
            for (rscUrl in pageUrl.rscUrls()) {
                enqueueFromUrl(rscUrl, pageUrl, rscHeaders(pageUrl))
            }
        }

        suspend fun emitDirect(url: String, label: String?, referer: String) {
            val fixed = normalizeMediaUrl(url) ?: return
            val key = fixed.normalizedMediaKey()
            if (!emitted.add(key)) return
            val qualityLabel = label?.cleanServerLabel().orEmpty().ifBlank { fixed.qualityLabelFromUrl() }
            val quality = qualityLabel.takeIf { it.isNotBlank() }?.let { getQualityFromName(it) }
                ?: fixed.parseQuality()
                ?: Qualities.Unknown.value

            if (fixed.contains(".m3u8", true)) {
                val links = M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = fixed,
                    referer = referer,
                    headers = siteHeaders
                )
                for (link in links) {
                    callback(link)
                    hasLinks = true
                }
            } else {
                callback(
                    newExtractorLink(
                        source = name,
                        name = listOf(name, qualityLabel).filter { it.isNotBlank() }.joinToString(" "),
                        url = fixed,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = quality
                        this.headers = siteHeaders + mapOf("Range" to "bytes=0-")
                    }
                )
                hasLinks = true
            }
        }

        suspend fun emitExtractor(url: String, label: String?, referer: String) {
            val fixed = normalizeMediaUrl(url) ?: return
            if (fixed.isDirectMedia()) {
                emitDirect(fixed, label, referer)
                return
            }
            val key = fixed.normalizedMediaKey()
            if (!emitted.add(key)) return

            loadExtractor(fixed, referer, subtitleCallback) { link ->
                hasLinks = true
                callback(link)
            }
        }

        var guard = 0
        while (queue.isNotEmpty() && guard < 120) {
            guard++
            val candidate = queue.removeFirst()
            val fixed = normalizeMediaUrl(candidate.url) ?: continue
            if (!visited.add(fixed.normalizedMediaKey())) continue
            val referer = candidate.referer ?: pageUrl

            if (fixed.isDirectMedia()) {
                emitDirect(fixed, candidate.label, referer)
                continue
            }

            if (!fixed.startsWith(mainUrl, true)) {
                emitExtractor(fixed, candidate.label, referer)
                if (fixed.shouldInlineResolve()) {
                    enqueueFromUrl(fixed, referer, playerHeaders(fixed))
                }
                continue
            }

            if (fixed.isInternalPlayerPage()) {
                enqueueFromUrl(fixed, referer)
            }
        }

        return hasLinks
    }

    private fun buildPageUrl(data: String, page: Int): String {
        if (page <= 1) return data
        val separator = if (data.contains("?")) "&" else "?"
        return "${data.trimEnd('/')}$separator${"page=$page"}"
    }

    private fun Document.parseAnimeItems(preferMovie: Boolean = false): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val scopes = listOfNotNull(selectFirst("main"), selectFirst("#app"), selectFirst("body")).distinct()

        for (scope in scopes) {
            val cards = scope.select(
                "article, .anime-card, .anime-item, .episode-card, .card, .grid > div, .list > div, " +
                    "li:has(a[href*=/anime/]), li:has(a[href*=/watch/]), " +
                    "a[href*=/anime/], a[href*=/watch/]"
            )
            for (card in cards) {
                val response = card.toSearchResponse(preferMovie) ?: continue
                results[response.url] = response
            }
            if (results.isNotEmpty()) break
        }

        return results.values.toList()
    }

    private fun Element.toSearchResponse(preferMovie: Boolean = false): SearchResponse? {
        val card = bestCard()
        val detailAnchor = card.selectFirst("a[href*=/anime/], a[href*='/anime/']")
        val watchAnchor = card.selectFirst("a[href*=/watch/], a[href*='/watch/']")
        val anchor = detailAnchor ?: watchAnchor ?: if (tagName().equals("a", true)) this else return null
        val href = anchor.attr("href").trim().toAbsoluteUrl() ?: return null
        if (!href.isAnimeOrWatchUrl()) return null
        if (href.contains("/genre/", true) || href.contains("/jadwal", true)) return null

        val title = listOf(
            card.selectFirst("h1, h2, h3, .title, .judul, .anime-title, .entry-title")?.text(),
            anchor.attr("title"),
            card.selectFirst("img[alt]")?.attr("alt"),
            anchor.text(),
            card.text()
        ).firstCleanTitle() ?: return null

        if (title.isBlockedTitle()) return null

        val poster = card.bestImage()?.toAbsoluteUrl()
        val episode = card.text().parseEpisodeNumber() ?: href.parseEpisodeNumber()
        val tvType = when {
            preferMovie || title.looksLikeMovie() || href.contains("movie", true) -> TvType.AnimeMovie
            title.contains("OVA", true) || title.contains("Special", true) -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title.cleanSeriesTitle(), href, tvType) {
            posterUrl = poster
            episode?.let { addSub(it) }
        }
    }

    private fun Element.bestCard(): Element {
        if (selectFirst("img") != null && selectFirst("a[href]") != null) return this
        return parents().firstOrNull { parent ->
            parent.selectFirst("img") != null && parent.selectFirst("a[href]") != null && parent.text().length in 2..1200
        } ?: this
    }

    private fun Document.parseEpisodeLinks(): List<Episode> {
        val selectors = listOf(
            "section:has(a[href*=/watch/]) a[href*=/watch/]",
            "main a[href*=/watch/]",
            "article a[href*=/watch/]",
            "a[href*=/watch/]"
        )
        return selectors.asSequence()
            .flatMap { select(it).asSequence() }
            .mapNotNull { it.toEpisodeOrNull() }
            .distinctBy { it.data }
            .toList()
    }

    private fun Element.toEpisodeOrNull(): Episode? {
        val href = attr("href").trim().toAbsoluteUrl() ?: return null
        return href.toEpisodeFromUrl(text().trim().ifBlank { attr("title") })
    }

    private fun String.toEpisodeFromUrl(label: String? = null): Episode? {
        val href = toAbsoluteUrl() ?: return null
        if (!href.contains("/watch/", true)) return null
        val rawText = label?.trim()?.ifBlank { null } ?: href.slugTitle()
        val episodeNumber = rawText.parseEpisodeNumber() ?: href.parseEpisodeNumber()
        return newEpisode(href) {
            this.name = rawText.parseEpisodeName() ?: episodeNumber?.let { "Episode $it" } ?: rawText.cleanTitle()
            this.episode = episodeNumber
        }
    }

    private fun generateEpisodeRange(seriesUrl: String, title: String, poster: String?, range: IntRange): List<Episode> {
        val safeRange = range.first.coerceAtLeast(1)..range.last.coerceAtMost(2000)
        return safeRange.map { number ->
            newEpisode("$seriesUrl#episode=$number") {
                this.name = "Episode $number"
                this.episode = number
                this.posterUrl = poster
            }
        }
    }

    private fun Document.extractServerCandidates(referer: String): List<ServerCandidate> {
        val results = linkedMapOf<String, ServerCandidate>()

        fun add(rawUrl: String?, label: String? = null) {
            val fixed = rawUrl?.trim()?.trim('"', '\'', ' ')?.htmlDecode()?.unescapeJs()?.toAbsoluteUrl() ?: return
            if (fixed.isBlank() || fixed == mainUrl || fixed == referer) return
            if (fixed.startsWith("javascript:", true) || fixed.startsWith("data:", true)) return
            if (!fixed.isPotentialPlayerUrl()) return
            results[fixed] = ServerCandidate(fixed, label, referer)
        }

        for (iframe in select("iframe[src], embed[src]")) {
            add(iframe.attr("src"), iframe.attr("title").ifBlank { iframe.attr("name") })
        }

        for (source in select("video[src], source[src]")) {
            add(source.attr("src"), source.attr("label").ifBlank { source.attr("res") })
        }

        val dataSelector = "[data-src], [data-url], [data-link], [data-iframe], [data-embed], [data-player], [data-video], [data-file]"
        for (element in select(dataSelector)) {
            val label = element.text().ifBlank { element.attr("aria-label") }.ifBlank { element.attr("title") }
            val attrs = listOf("data-src", "data-url", "data-link", "data-iframe", "data-embed", "data-player", "data-video", "data-file")
            for (attr in attrs) add(element.attr(attr), label)
        }

        for (option in select("option[value]")) {
            val value = option.attr("value")
            add(value, option.text())
            decodePossibleBase64(value)?.let { decoded ->
                val parsed = Jsoup.parse(decoded)
                for (candidate in parsed.extractServerCandidates(referer)) {
                    add(candidate.url, option.text().ifBlank { candidate.label.orEmpty() })
                }
            }
        }

        val html = html().htmlDecode().unescapeJs()
        val urlRegex = Regex("""https?:\/\/[^'"<>\s\\]+""", RegexOption.IGNORE_CASE)
        for (match in urlRegex.findAll(html)) {
            add(match.value, match.value.qualityLabelFromUrl())
        }

        val keyRegex = Regex("""(?:src|url|link|file|iframe|embed|player|video)\s*[:=]\s*['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
        for (match in keyRegex.findAll(html)) {
            add(match.groupValues.getOrNull(1), match.value.qualityLabelFromUrl())
        }

        val b64Regex = Regex("""(?:atob\(|base64_decode\(|['\"])([A-Za-z0-9+/=_-]{32,})(?:['\"]|\))""")
        for (match in b64Regex.findAll(html)) {
            val decoded = decodePossibleBase64(match.groupValues[1]) ?: continue
            val parsed = Jsoup.parse(decoded)
            for (candidate in parsed.extractServerCandidates(referer)) {
                add(candidate.url, candidate.label)
            }
        }

        return results.values.toList()
    }

    private fun String.extractServerCandidatesFromText(referer: String): List<ServerCandidate> {
        val results = linkedMapOf<String, ServerCandidate>()

        fun add(rawUrl: String?, label: String? = null) {
            val fixed = rawUrl
                ?.trim()
                ?.trim('"', '\'', ' ', ',', ')', ']', '}')
                ?.basicHtmlDecode()
                ?.unescapeJs()
                ?.toAbsoluteUrl()
                ?: return
            if (fixed.isBlank() || fixed == mainUrl || fixed == referer) return
            if (fixed.startsWith("javascript:", true) || fixed.startsWith("data:", true)) return
            if (!fixed.isPotentialPlayerUrl()) return
            results[fixed] = ServerCandidate(fixed, label, referer)
        }

        val variants = linkedSetOf<String>()
        variants.add(this)
        variants.add(basicHtmlDecode())
        variants.add(unescapeJs())
        variants.add(basicHtmlDecode().unescapeJs())
        runCatching { getAndUnpack(this) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { unpacked ->
            variants.add(unpacked)
            variants.add(unpacked.basicHtmlDecode().unescapeJs())
        }

        for (source in variants) {
            val cleaned = source.basicHtmlDecode().unescapeJs()

            Regex(
                """(?is)["']server_name["']\s*:\s*["']([^"']+)["'].*?["']embed_url["']\s*:\s*["']([^"']+)["']"""
            ).findAll(cleaned).forEach { match ->
                add(match.groupValues.getOrNull(2), match.groupValues.getOrNull(1))
            }

            Regex(
                """(?is)["']label["']\s*:\s*["']([^"']+)["'].*?["'](?:file|url|src)["']\s*:\s*["']([^"']+)["']"""
            ).findAll(cleaned).forEach { match ->
                add(match.groupValues.getOrNull(2), match.groupValues.getOrNull(1))
            }

            Regex(
                """(?:src|url|link|file|iframe|embed|player|video|contentUrl|embedUrl)\s*["']?\s*[:=]\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).findAll(cleaned).forEach { match ->
                add(match.groupValues.getOrNull(1), match.value.qualityLabelFromUrl())
            }

            Regex("""https?://[^"'<>\s\\]+""", RegexOption.IGNORE_CASE).findAll(cleaned).forEach { match ->
                add(match.value, match.value.qualityLabelFromUrl())
            }

            Regex("""(?:atob\(|base64_decode\(|["'])([A-Za-z0-9+/=_-]{32,})(?:["']|\))""").findAll(cleaned).forEach { match ->
                val decoded = decodePossibleBase64(match.groupValues[1]) ?: return@forEach
                decoded.extractServerCandidatesFromText(referer).forEach { add(it.url, it.label) }
            }
        }

        return results.values.toList()
    }

    private fun String.extractWatchUrls(): List<String> {
        val cleaned = basicHtmlDecode().unescapeJs().replace("\\/", "/")
        val results = linkedSetOf<String>()
        Regex("""https?://animemovies\.org/watch/[^"'<>\s\\]+""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .forEach { results.add(it.value.trimEnd(',', ')', ']', '}')) }
        Regex("""["'](/watch/[^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .forEach { match -> match.groupValues.getOrNull(1)?.toAbsoluteUrl()?.let { results.add(it) } }
        Regex("""(?:href|url|to)\s*[:=]\s*["'](/watch/[^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .forEach { match -> match.groupValues.getOrNull(1)?.toAbsoluteUrl()?.let { results.add(it) } }
        return results.toList()
    }

    private fun Document.hasNextPage(page: Int): Boolean {
        val nextPage = page + 1
        return select("a[href]").any {
            val href = it.attr("href")
            href.contains("page=$nextPage")
        }
    }

    private fun Document.bestTitle(): String? {
        return selectFirst("h1, .title h1, .entry-title, .anime-title")?.text()?.trim()
            ?: selectFirst("meta[property=og:title], meta[name=twitter:title]")?.attr("content")?.trim()
            ?: selectFirst("title")?.text()?.substringBefore(" - ")?.trim()
    }

    private fun Document.bestPoster(): String? {
        return listOfNotNull(
            selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content"),
            selectFirst(".poster img, .thumb img, .thumbnail img, article img, main img")?.bestImage()
        ).firstOrNull { it.isNotBlank() }?.toAbsoluteUrl()
    }

    private fun Document.bestPlot(): String? {
        return selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst(".sinopsis, .synopsis, .description, .desc, .entry-content p, article p")?.text()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun Element.bestImage(): String? {
        val image = if (tagName().equals("img", true)) this else selectFirst("img")
        return image?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-original")?.takeIf { it.isNotBlank() }
            ?: image?.attr("srcset")?.substringBefore(" ")?.takeIf { it.isNotBlank() }
            ?: image?.attr("src")?.takeIf { it.isNotBlank() }
    }

    private fun String.toAbsoluteUrl(): String? {
        val raw = trim().trim('"', '\'', ' ')
        if (raw.isBlank() || raw == "#") return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("/") -> mainUrl.trimEnd('/') + raw
            raw.startsWith("./") -> mainUrl.trimEnd('/') + "/" + raw.removePrefix("./")
            raw.startsWith("../") -> mainUrl.trimEnd('/') + "/" + raw.substringAfterLast("../")
            raw.contains(".") && !raw.contains(" ") -> "https://$raw"
            else -> null
        }
    }

    private fun normalizeMediaUrl(raw: String?): String? {
        return raw?.basicHtmlDecode()?.unescapeJs()?.replace("\\/", "/")?.toAbsoluteUrl()
    }

    private fun String.isAnimeOrWatchUrl(): Boolean {
        return startsWith(mainUrl, true) && (contains("/anime/", true) || contains("/watch/", true))
    }

    private fun String.isPotentialPlayerUrl(): Boolean {
        val lower = lowercase()
        return lower.isDirectMedia() ||
            lower.contains("/embed") ||
            lower.contains("iframe") ||
            lower.contains("player") ||
            lower.contains("vidhide") ||
            lower.contains("filedon") ||
            lower.contains("mega") ||
            lower.contains("ondes") ||
            lower.contains("desustream") ||
            lower.contains("stream") ||
            lower.contains("dood") ||
            lower.contains("filemoon") ||
            lower.contains("mp4upload") ||
            lower.contains("googlevideo.com/videoplayback") ||
            lower.contains("cloudflarestorage.com") ||
            lower.contains("r2.cloudflarestorage.com") ||
            lower.contains("voe") ||
            lower.contains("mixdrop") ||
            lower.isInternalPlayerPage()
    }

    private fun String.isInternalPlayerPage(): Boolean {
        val lower = lowercase()
        return lower.startsWith(mainUrl.lowercase()) &&
            (lower.contains("/watch/") || lower.contains("/api/") || lower.contains("_rsc=")) &&
            !lower.contains("/anime/") &&
            !lower.contains("/genre/") &&
            !lower.contains("/jadwal")
    }

    private fun String.isDirectMedia(): Boolean {
        val lower = lowercase()
        return lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            lower.contains(".webm") ||
            lower.contains(".mkv") ||
            lower.contains("googlevideo.com/videoplayback") ||
            lower.contains("mime=video") ||
            lower.contains("cloudflarestorage.com")
    }

    private fun String.shouldInlineResolve(): Boolean {
        val lower = lowercase()
        return lower.contains("odvidhide") ||
            lower.contains("vidhide") ||
            lower.contains("filedon") ||
            lower.contains("desustream") ||
            lower.contains("ondes")
    }

    private fun String.normalizedMediaKey(): String {
        return substringBefore("&Expires=")
            .substringBefore("?Expires=")
            .substringBefore("&X-Amz-Signature=")
    }

    private fun String.cleanTitle(): String {
        return htmlDecode()
            .replace(Regex("(?i)\\s*subtitle\\s*indonesia.*$"), "")
            .replace(Regex("(?i)\\s*sub\\s*indo.*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', ':')
    }

    private fun String.cleanSeriesTitle(): String {
        return cleanTitle()
            .replace(Regex("(?i)\\s*\\(?\\s*episode\\s*\\d{1,4}\\s*[–—-]\\s*\\d{1,4}\\s*\\)?"), "")
            .replace(Regex("(?i)\\s*\\(?\\s*eps?\\s*\\d{1,4}\\s*\\)?"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', ':')
    }

    private fun List<String?>.firstCleanTitle(): String? {
        for (item in this) {
            val clean = item?.cleanTitle()?.takeIf { it.length >= 2 } ?: continue
            return clean
        }
        return null
    }

    private fun String.isBlockedTitle(): Boolean {
        val normalized = lowercase().trim()
        return normalized in setOf(
            "beranda", "daftar anime", "genre", "jadwal", "masuk", "daftar", "tonton", "detail",
            "lihat semua", "hubungi kami", "dmca", "kontak", "kebijakan privasi", "anime movies", "animemovies"
        )
    }

    private fun String.slugTitle(): String {
        return substringBefore("?").substringBefore("#").trimEnd('/').substringAfterLast('/').replace('-', ' ').cleanTitle().ifBlank { name }
    }

    private fun String.slugOnly(): String {
        return substringBefore("?").substringBefore("#").trimEnd('/').substringAfterLast('/')
            .replace(Regex("(?i)-subtitle-indonesia$"), "")
            .replace(Regex("(?i)-sub-indo$"), "")
            .replace(Regex("(?i)-episode-\\d{1,4}-\\d{1,4}.*$"), "")
            .trim('-')
    }

    private fun String.parseYear(): Int? {
        return Regex("""\b(?:19|20)\d{2}\b""").find(this)?.value?.toIntOrNull()
    }

    private fun String.parseEpisodeNumber(): Int? {
        val match = Regex("""(?i)[?#&]episode=(\d{1,4})""").find(this)
            ?: Regex("""(?i)(?:episode|ep|e)\s*[-:]?\s*(\d{1,4})""").find(this)
            ?: Regex("""(?i)(?:episode|ep|e)[-_]?(\d{1,4})""").find(this)
            ?: Regex("""-(\d{1,4})(?:-|$)""").find(this)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun String.parseEpisodeName(): String? {
        val episode = parseEpisodeNumber() ?: return null
        return "Episode $episode"
    }

    private fun String.parseEpisodeRange(): IntRange? {
        val regexes = listOf(
            Regex("""(?i)episode\s*(\d{1,4})\s*[–—-]\s*(\d{1,4})"""),
            Regex("""(?i)eps?\s*(\d{1,4})\s*[–—-]\s*(\d{1,4})"""),
            Regex("""(?i)(\d{1,4})\s*eps""")
        )
        for (regex in regexes) {
            val match = regex.find(this) ?: continue
            if (match.groupValues.size >= 3) {
                val start = match.groupValues[1].toIntOrNull() ?: continue
                val end = match.groupValues[2].toIntOrNull() ?: continue
                if (end >= start) return start..end
            } else {
                val end = match.groupValues[1].toIntOrNull() ?: continue
                if (end > 1) return 1..end
            }
        }
        return null
    }

    private fun String.toShowStatus(): ShowStatus {
        return when {
            contains("ongoing", true) || contains("sedang tayang", true) -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    private fun String.looksLikeMovie(): Boolean {
        val lower = lowercase()
        return lower.contains(" movie") ||
            lower.contains("film") ||
            lower.contains("gekijouban") ||
            lower.contains("last game") ||
            lower.contains("kimi no na wa") ||
            lower.contains("kizumonogatari") ||
            (lower.contains(" bd") && parseEpisodeRange() == null)
    }

    private fun String.htmlDecode(): String {
        return Jsoup.parse(this).text()
    }

    private fun String.unescapeJs(): String {
        var output = this
        repeat(2) {
            output = Regex("""\\u([0-9a-fA-F]{4})""").replace(output) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
        }
        return output
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")
    }

    private fun decodePossibleBase64(value: String?): String? {
        val raw = value?.trim()?.trim('"', '\'', ' ') ?: return null
        if (raw.length < 16) return null
        return runCatching {
            val normalized = raw.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT))
        }.getOrNull()?.takeIf { it.contains("<iframe", true) || it.contains("http", true) }
    }

    private fun String.parseQuality(): Int? {
        return Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE).find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun String.qualityLabelFromUrl(): String {
        return parseQuality()?.let { "${it}p" }.orEmpty()
    }

    private fun String.cleanServerLabel(): String {
        return htmlDecode().replace(Regex("\\s+"), " ").trim()
    }

    private fun String.basicHtmlDecode(): String {
        return replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#x22;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun playerHeaders(url: String): Map<String, String> {
        val lower = url.lowercase()
        return siteHeaders + when {
            lower.contains("odvidhide") || lower.contains("vidhide") -> mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer" to "$mainUrl/"
            )
            lower.contains("filedon") -> mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer" to "$mainUrl/"
            )
            else -> siteHeaders
        }
    }

    private fun String.rscUrls(): List<String> {
        val base = substringBefore("#")
        val separator = if (base.contains("?")) "&" else "?"
        return listOf(
            "${base}${separator}_rsc=1",
            "${base}${separator}_rsc=ndb2r",
            "${base}${separator}_rsc=1kfs1"
        )
    }

    private fun String.guessWatchUrls(title: String, episode: Int): List<String> {
        val baseSlug = slugOnly()
        val titleSlug = title.toSlug()
        val acr = title.acronymSlug()
        val numbers = listOf(episode.toString(), episode.toString().padStart(2, '0'), episode.toString().padStart(3, '0')).distinct()
        val roots = listOf(baseSlug, titleSlug, acr).filter { it.isNotBlank() }.distinct()
        val guesses = linkedSetOf<String>()
        for (root in roots) {
            for (number in numbers) {
                guesses.add("$mainUrl/watch/$root-episode-$number-sub-indo")
                guesses.add("$mainUrl/watch/$root-episode-$number-subtitle-indonesia")
                guesses.add("$mainUrl/watch/$root-ep-$number-sub-indo")
            }
        }
        return guesses.toList()
    }

    private fun String.toSlug(): String {
        return cleanSeriesTitle()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    private fun String.acronymSlug(): String {
        return cleanSeriesTitle()
            .lowercase()
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in setOf("no", "ni", "to", "the", "a") }
            .joinToString("") { token ->
                if (token.all { it.isDigit() }) token else token.firstOrNull()?.toString().orEmpty() + token.filter { it.isDigit() }
            }
            .trim('-')
    }

    private fun String.searchTokens(): List<String> {
        return lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
    }

    private fun String.matchesSearchTokens(tokens: List<String>, url: String): Boolean {
        val target = "$this ${url.substringAfterLast('/').replace('-', ' ')}".lowercase()
        return tokens.all { target.contains(it) }
    }

    private data class ServerCandidate(
        val url: String,
        val label: String?,
        val referer: String?
    )
}