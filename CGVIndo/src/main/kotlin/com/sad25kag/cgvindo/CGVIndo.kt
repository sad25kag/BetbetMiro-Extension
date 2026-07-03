package com.sad25kag.cgvindo

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

class CGVIndo : MainAPI() {
    override var mainUrl = "http://167.71.211.231"
    override var name = "CGVIndo"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    private val defaultHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "/lates" to "Latest",
        "/genre/action/" to "Action",
        "/genre/drama/" to "Drama",
        "/genre/drama-asia/" to "Drama Asia",
        "/genre/drama-serial-barat/" to "Drama Serial Barat",
        "/genre/drama-korea/" to "Drama Korea"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val document = try {
            app.get(url, headers = defaultHeaders, referer = mainUrl).document
        } catch (_: Throwable) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val results = parseCards(document)
        return newHomePageResponse(request.name, results, hasNext = hasNextPage(document, page))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/search/$encoded/",
            "$mainUrl/page/1/?s=$encoded"
        )
        val out = linkedMapOf<String, SearchResponse>()
        urls.forEach { url ->
            val document = try {
                app.get(url, headers = defaultHeaders, referer = mainUrl).document
            } catch (_: Throwable) {
                return@forEach
            }
            parseCards(document)
                .filter { it.name.contains(keyword, ignoreCase = true) || it.url.contains(keyword.slug(), ignoreCase = true) }
                .forEach { out[it.url.normalKey()] = it }
            if (out.isNotEmpty()) return out.values.take(60)
        }
        return out.values.take(60)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url, mainUrl) ?: return null
        val response = try {
            app.get(pageUrl, headers = defaultHeaders, referer = mainUrl)
        } catch (_: Throwable) {
            return null
        }
        val document = response.document
        val html = response.text.ifBlank { document.html() }.normalizeEscapes()
        val title = cleanTitle(
            document.selectFirst("h1.entry-title, h1.post-title, h1.title, h1, meta[property=og:title], title")
                ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
        ).ifBlank { titleFromUrl(pageUrl) }
        if (title.isBlank()) return null

        val poster = findPoster(document, pageUrl)
        val text = cleanText(document.text())
        val description = cleanDescription(
            document.selectFirst("meta[property=og:description], meta[name=description], .entry-content p, .post-content p, .content p, .sinopsis, .synopsis, .description, .desc, [itemprop=description]")
                ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
        )
        val tags = document.select("a[href*='/genre/']")
            .map { cleanText(it.text()).substringBefore("(").trim() }
            .filter { it.length in 2..40 && !it.equals("Home", true) && !it.equals("Nonton", true) && !it.equals("Download", true) }
            .distinct()
            .take(20)
        val actors = document.select("a[href*='/cast/'], a[href*='/actor/'], a[href*='/stars/']")
            .map { cleanText(it.text()) }
            .filter { it.length in 2..60 }
            .distinct()
            .take(24)
        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(text)?.value?.toIntOrNull()
        val rating = document.selectFirst("[itemprop=ratingValue], .rating, .score, .imdb, .vote")?.text()?.replace(",", ".")
            ?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }
        val duration = Regex("""(?i)(\d{1,3})\s*(?:min|menit|m)\b""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val episodes = parseEpisodes(document, pageUrl)
        val recommendations = parseRecommendations(document, pageUrl)
        val type = inferType(pageUrl, title, text, episodes.size)

        return if (type == TvType.TvSeries && episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                rating?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieLoadResponse(title, pageUrl, type, pageUrl) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                rating?.let { this.score = Score.from10(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val detailUrl = fixUrl(data, mainUrl) ?: return false
        val emitted = linkedSetOf<String>()
        val visited = linkedSetOf<String>()
        val queue = ArrayDeque<Pair<String, String>>()
        var found = false

        suspend fun emitDirect(url: String, referer: String, source: String = name): Boolean {
            val fixed = fixUrl(url, referer) ?: return false
            if (!fixed.isPlayableMedia() || fixed.isTrailerOrSocialUrl()) return false
            val key = fixed.substringBefore("#")
            if (!emitted.add(key)) return false
            val headers = sourceHeaders(fixed, referer)
            if (fixed.isM3u8()) {
                val links = try { generateM3u8(source, fixed, referer, headers = headers) } catch (_: Throwable) { emptyList() }
                links.forEach { link ->
                    val linkKey = link.url.substringBefore("#")
                    if (!link.url.isTrailerOrSocialUrl() && emitted.add(linkKey)) callback(link)
                }
                if (links.isNotEmpty()) return true
            }
            callback(newExtractorLink(source, source, fixed, if (fixed.isM3u8()) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = referer
                this.quality = qualityFromUrl(fixed)
                this.headers = headers
            })
            return true
        }

        suspend fun emitExtractor(url: String, referer: String): Boolean {
            val fixed = fixUrl(url, referer) ?: return false
            if (!fixed.isPlaybackCandidate()) return false
            if (fixed.isPlayableMedia()) return emitDirect(fixed, referer, playerSourceName(fixed))
            var localFound = false
            try {
                loadExtractor(fixed, referer, subtitleCallback) { link ->
                    val key = link.url.substringBefore("#")
                    if (!link.url.isTrailerOrSocialUrl() && emitted.add(key)) {
                        localFound = true
                        callback(link)
                    }
                }
            } catch (_: Throwable) {
            }
            return localFound
        }

        suspend fun inspectPlayer(url: String, referer: String): List<String> {
            val fixed = fixUrl(url, referer) ?: return emptyList()
            if (!fixed.isPlaybackCandidate() || !visited.add(fixed.normalKey())) return emptyList()
            if (fixed.isPlayableMedia()) {
                if (emitDirect(fixed, referer, playerSourceName(fixed))) found = true
                return emptyList()
            }

            if (emitExtractor(fixed, referer)) {
                found = true
                return emptyList()
            }

            // q8y5z should be handed to CloudStream extractor only.
            // Manually following it inside the provider can cause infinite loading.
            if (fixed.isExtractorTerminalHost()) return emptyList()
            if (!shouldFollow(fixed) && !fixed.shouldFetchEmbedPage()) return emptyList()

            val response = try {
                app.get(fixed, headers = defaultHeaders + mapOf("Referer" to referer), referer = referer)
            } catch (_: Throwable) {
                return emptyList()
            }
            val document = response.document
            val html = response.text.ifBlank { document.html() }.normalizeEscapes()
            collectSubtitles(document, fixed, subtitleCallback)

            val links = linkedSetOf<String>()
            collectByseEmbed(document, html, fixed).forEach { links.add(it) }
            collectLinksFromHtml(html, fixed).forEach { links.add(it) }
            return links.filter { it.isPlaybackCandidate() }
        }

        // Muvipro playback: detail/player data -> AJAX action=muvipro_player_content
        // -> decode server option/iframe -> pass iframe/player URL to loadExtractor.
        // Do not run q8y5z WebView in this provider path.
        val detailResponse = try {
            app.get(detailUrl, headers = defaultHeaders, referer = mainUrl)
        } catch (_: Throwable) {
            return false
        }
        val detailDocument = detailResponse.document
        val detailHtml = detailResponse.text.ifBlank { detailDocument.html() }.normalizeEscapes()
        collectSubtitles(detailDocument, detailUrl, subtitleCallback)

        collectMuviproPlayers(detailDocument, detailUrl).forEach { queue.add(it to detailUrl) }
        collectPlayerContainerLinks(detailDocument, detailUrl).forEach { queue.add(it to detailUrl) }
        if (queue.isEmpty()) {
            collectByseEmbed(detailDocument, detailHtml, detailUrl).forEach { queue.add(it to detailUrl) }
        }

        var rounds = 0
        while (queue.isNotEmpty() && rounds < 48) {
            rounds++
            val (rawUrl, referer) = queue.removeFirst()
            val url = fixUrl(rawUrl, referer) ?: continue
            if (!url.isPlaybackCandidate() || url.isTrailerOrSocialUrl() || url.isNoiseUrl()) continue
            when {
                url.isPlayableMedia() -> if (emitDirect(url, referer, playerSourceName(url))) found = true
                else -> {
                    if (emitExtractor(url, referer)) found = true
                    if (!url.isExtractorTerminalHost()) {
                        inspectPlayer(url, referer).forEach { next ->
                            val nextFixed = fixUrl(next, url) ?: return@forEach
                            when {
                                nextFixed.isPlayableMedia() -> if (emitDirect(nextFixed, url, playerSourceName(nextFixed))) found = true
                                nextFixed.isPlaybackCandidate() && !visited.contains(nextFixed.normalKey()) -> queue.add(nextFixed to url)
                            }
                        }
                    }
                }
            }
        }
        return found
    }


    private fun fixUrl(raw: String?, baseUrl: String = mainUrl): String? {
        val value = raw?.trim().orEmpty()
            .normalizeEscapes()
            .takeIf { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("javascript:", true) }
            ?: return null
        return when {
            value.startsWith("//") -> "http:$value"
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("/") -> mainUrl.trimEnd('/') + value
            else -> {
                val base = baseUrl.ifBlank { mainUrl }
                val root = when {
                    base.endsWith("/") -> base
                    base.substringAfterLast('/', "").contains('.') -> base.substringBeforeLast('/') + "/"
                    else -> base.trimEnd('/') + "/"
                }
                root + value
            }
        }
    }

    private fun pageUrl(path: String, page: Int): String {
        val fixed = fixUrl(path, mainUrl) ?: mainUrl
        return if (page <= 1) fixed else fixed.trimEnd('/') + "/page/$page/"
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        // Evidence HAR: archive/category pages use Muvipro article.item-infinite cards.
        document.select("article.item-infinite, article.item, article.has-post-thumbnail").forEach {
            it.toSearchResult()?.let { item -> results[item.url.normalKey()] = item }
        }

        if (results.size < 4) {
            document.select(CARD_SELECTOR).forEach { it.toSearchResult()?.let { item -> results[item.url.normalKey()] = item } }
        }

        return results.values.take(80)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (`is`("a[href]")) this else selectFirst("h1 a[href], h2 a[href], h3 a[href], .entry-title a[href], .title a[href], .name a[href], a[href][title], a[href]") ?: return null
        val href = fixUrl(anchor.attr("href"), mainUrl) ?: return null
        if (!isContentUrl(href)) return null
        val container = anchor.closest("article.item-infinite, article.item, article.has-post-thumbnail, article, .post, .movie, .film, .item, .card, .result, .thumbnail, li, div") ?: this
        val mainAnchor = container.selectFirst("h1.entry-title a[href], h2.entry-title a[href], h3.entry-title a[href], a[rel=bookmark][href], .content-thumbnail a[href]") ?: anchor
        val contentHref = fixUrl(mainAnchor.attr("href"), mainUrl) ?: href
        if (!isContentUrl(contentHref)) return null
        val image = container.selectFirst(".content-thumbnail img[data-src], .content-thumbnail img[src], img[data-src], img[data-original], img[data-lazy-src], img[src], img[srcset]") ?: mainAnchor.selectFirst("img")
        val title = listOf(
            container.selectFirst("h1.entry-title, h2.entry-title, h3.entry-title, h1, h2, h3, .post-title, .title, .name")?.text(),
            mainAnchor.attr("title").substringAfter("Permalink ke:").trim(),
            image?.attr("alt"),
            mainAnchor.text(),
            titleFromUrl(contentHref)
        ).firstOrNull { isUsefulTitle(it) }?.let { cleanTitle(it) } ?: return null
        val poster = image?.imageUrl(mainUrl) ?: container.styleImage(mainUrl)
        val text = cleanText(container.text())
        val type = inferType(href, title, text, 0)
        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(text)?.value?.toIntOrNull()
        val score = container.selectFirst(".rating, .score, .imdb, .vote")?.text()?.replace(",", ".")
            ?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }
        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, contentHref, TvType.TvSeries) {
                posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, contentHref, type) {
                posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
        }
    }

    private fun parseEpisodes(document: Document, baseUrl: String): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()
        document.select(".episode-list a[href], .episodes a[href], .episodios a[href], .season a[href], .seasons a[href], [class*=episode] a[href], [id*=episode] a[href]")
            .forEachIndexed { index, element ->
                val href = fixUrl(element.attr("href"), baseUrl) ?: return@forEachIndexed
                if (!isContentUrl(href) && !href.contains("episode", true) && !href.contains("eps", true)) return@forEachIndexed
                val label = cleanText(element.text()).ifBlank { titleFromUrl(href) }
                val ep = Regex("""(?i)(?:episode|eps|ep)\s*[-:.]?\s*(\d{1,4})""").find("$label $href")?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""(?i)(?:/|-)(\d{1,4})(?:/|$)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: (index + 1)
                episodes[href] = newEpisode(href) {
                    name = label.ifBlank { "Episode $ep" }
                    episode = ep
                }
            }
        return episodes.values.sortedBy { it.episode ?: 9999 }
    }

    private fun parseRecommendations(document: Document, currentUrl: String): List<SearchResponse> =
        document.select(".related, .recommend, .rekomendasi, .similar, section")
            .flatMap { section -> section.select(CARD_SELECTOR).mapNotNull { it.toSearchResult() } }
            .distinctBy { it.url.normalKey() }
            .filterNot { it.url.normalKey() == currentUrl.normalKey() }
            .take(16)


    private suspend fun collectMuviproPlayers(document: Document, pageUrl: String): List<String> {
        val links = linkedSetOf<String>()
        val pageHtml = document.html().normalizeEscapes()
        val postId = document.selectFirst("[data-post-id], [data-post_id], [data-post]")?.let { element ->
            element.attr("data-post-id").ifBlank { element.attr("data-post_id").ifBlank { element.attr("data-post") } }
        }?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("article[id^=post-]")?.id()?.substringAfter("post-")
            ?: Regex("""(?i)(?:postid-|post-)(\d{2,})""").find(pageHtml)?.groupValues?.getOrNull(1)
            ?: Regex("""(?i)(?:post[_-]?id|postId|post)['"]?\s*[:=]\s*['"]?(\d{2,})""").find(pageHtml)?.groupValues?.getOrNull(1)
            ?: return emptyList()
        val tabs = document.select("a[href^=\"#p\"], button[href^=\"#p\"], [data-tab^=\"p\"], [data-target^=\"#p\"]")
            .mapNotNull { element ->
                element.attr("href")
                    .ifBlank { element.attr("data-tab") }
                    .ifBlank { element.attr("data-target") }
                    .trim()
                    .removePrefix("#")
                    .takeIf { it.matches(Regex("""p\d+""")) }
            }
            .ifEmpty { (1..6).map { "p$it" } }
            .distinct()
        tabs.forEach { tab ->
            val body = try {
                app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf("action" to "muvipro_player_content", "tab" to tab, "post_id" to postId),
                    headers = defaultHeaders + mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "Accept" to "text/html, */*; q=0.01",
                        "Origin" to originOf(pageUrl),
                        "Referer" to pageUrl
                    ),
                    referer = pageUrl
                ).text
            } catch (_: Throwable) {
                ""
            }
            val normalized = body.unwrapAjaxHtml()
            if (normalized.isBlank()) return@forEach
            val parsed = Jsoup.parse(normalized, pageUrl)
            collectLinksFromHtml(normalized, pageUrl).forEach { links.add(it) }
            collectByseEmbed(parsed, normalized, pageUrl).forEach { links.add(it) }
        }
        return links.filter { it.isPlaybackCandidate() }
    }

    private suspend fun collectByseEmbed(document: Document, html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        val embeds = linkedSetOf<String>()
        document.select("iframe[src*='byseqekaho.com/e/'], iframe[data-src*='byseqekaho.com/e/'], a[href*='byseqekaho.com/e/']").forEach { element ->
            fixUrl(element.attr("src").ifBlank { element.attr("data-src").ifBlank { element.attr("href") } }, baseUrl)?.let { embeds.add(it) }
        }
        Regex("""https?://byseqekaho\.com/e/([A-Za-z0-9_-]+)[^'"\s<]*""").findAll(html).map { it.value }.forEach { embeds.add(it) }
        embeds.forEach { embed ->
            val code = Regex("""/e/([A-Za-z0-9_-]+)""").find(embed)?.groupValues?.getOrNull(1) ?: return@forEach
            val details = try {
                app.get(
                    "https://byseqekaho.com/api/videos/$code/embed/details",
                    headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json", "Referer" to embed),
                    referer = embed
                ).text
            } catch (_: Throwable) {
                ""
            }
            Regex("""\"embed_frame_url\"\s*:\s*\"([^\"]+)\"""").find(details)?.groupValues?.getOrNull(1)
                ?.normalizeEscapes()
                ?.let { links.add(it) }
            links.add(embed)
        }
        return links.filter { it.isPlaybackCandidate() }
    }

    private fun collectPlayerContainerLinks(document: Document, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        document.select(PLAYER_CONTAINER_SELECTOR).forEach { container ->
            val html = container.outerHtml().normalizeEscapes()
            collectLinksFromHtml(html, baseUrl).forEach { links.add(it) }
        }
        return links.filter { it.isPlaybackCandidate() }
    }

    private fun collectLinksFromHtml(html: String, baseUrl: String): List<String> {
        val normalized = html.normalizeEscapes()
        val links = linkedSetOf<String>()
        val parsed = runCatching { Jsoup.parse(normalized, baseUrl) }.getOrNull()
        parsed?.let { collectLinks(it, normalized, baseUrl).forEach { link -> links.add(link) } }
        Regex("""(?i)<(?:iframe|embed)[^>]+(?:src|data-src|data-litespeed-src)=['"]([^'"]+)['"]""").findAll(normalized)
            .mapNotNull { decodePossibleUrl(it.groupValues[1], baseUrl) }.forEach { links.add(it) }
        Regex("""(?i)['"]((?:https?:)?//[^'"]+?(?:\.m3u8|\.mp4|\.webm|/hls/[^'"]+|/stream/[^'"]+|/embed/[^'"]+|/player/[^'"]+|/e/[^'"]+|/v/[^'"]+)(?:\?[^'"]*)?)['"]""").findAll(normalized)
            .mapNotNull { decodePossibleUrl(it.groupValues[1], baseUrl) }.forEach { links.add(it) }
        Regex("""(?i)(?:file|source|src|url|link|hls|m3u8|embed|iframe|player)\s*[:=]\s*['"]([^'"]+)['"]""").findAll(normalized)
            .forEach { match -> decodePossibleUrls(match.groupValues[1], baseUrl).forEach { links.add(it) } }
        Regex("""(?i)(?:https?:)?//[^\s'"<>]+""").findAll(normalized)
            .forEach { match -> decodePossibleUrls(match.value, baseUrl).forEach { links.add(it) } }
        Regex("""(?i)atob\(['"]([^'"]+)['"]\)""").findAll(normalized)
            .mapNotNull { decodeBase64(it.groupValues[1]) }
            .forEach { decoded -> collectLinks(Jsoup.parse(decoded, baseUrl), decoded, baseUrl).forEach { links.add(it) } }
        return links.filter { it.isPlaybackCandidate() }
    }

    private fun collectLinks(document: Document, html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        document.select(SERVER_VALUE_SELECTOR).forEach { element ->
            SERVER_VALUE_ATTRS.forEach { attr ->
                element.attr(attr).takeIf { it.isNotBlank() }?.let { raw ->
                    decodePossibleUrls(raw, baseUrl).forEach { links.add(it) }
                }
            }
        }
        document.select(PLAYER_LINK_SELECTOR).forEach { element ->
            SERVER_VALUE_ATTRS.forEach { attr ->
                element.attr(attr).takeIf { it.isNotBlank() }?.let { raw ->
                    decodePossibleUrls(raw, baseUrl).forEach { links.add(it) }
                }
            }
        }
        Regex("""(?i)<(?:iframe|embed)[^>]+(?:src|data-src|data-litespeed-src)=['"]([^'"]+)['"]""").findAll(html)
            .forEach { match -> decodePossibleUrls(match.groupValues[1], baseUrl).forEach { links.add(it) } }
        Regex("""(?i)['"]((?:https?:)?//[^'"]+?(?:\.m3u8|\.mp4|\.webm|/hls/[^'"]+|/stream/[^'"]+|/embed/[^'"]+|/player/[^'"]+|/e/[^'"]+|/v/[^'"]+)(?:\?[^'"]*)?)['"]""").findAll(html)
            .forEach { match -> decodePossibleUrls(match.groupValues[1], baseUrl).forEach { links.add(it) } }
        Regex("""(?i)(?:file|source|src|url|link|hls|m3u8|embed|iframe|player)\s*[:=]\s*['"]([^'"]+)['"]""").findAll(html)
            .forEach { match -> decodePossibleUrls(match.groupValues[1], baseUrl).forEach { links.add(it) } }
        Regex("""(?i)(?:https?:)?//[^\s'"<>]+""").findAll(html)
            .forEach { match -> decodePossibleUrls(match.value, baseUrl).forEach { links.add(it) } }
        Regex("""(?i)atob\(['"]([^'"]+)['"]\)""").findAll(html)
            .mapNotNull { decodeBase64(it.groupValues[1]) }
            .forEach { decoded -> collectLinks(Jsoup.parse(decoded, baseUrl), decoded, baseUrl).forEach { links.add(it) } }
        return links.filter { it.isPlaybackCandidate() }.toList()
    }

    private fun collectSubtitles(document: Document, baseUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[src], a[href$=.srt], a[href$=.vtt]").forEach { element ->
            val url = fixUrl(element.attr("src").ifBlank { element.attr("href") }, baseUrl) ?: return@forEach
            val label = cleanText(element.attr("label").ifBlank { element.attr("srclang").ifBlank { element.text().ifBlank { "Subtitle" } } })
            subtitleCallback(SubtitleFile(label, url))
        }
    }

    private fun findPoster(document: Document, baseUrl: String): String? =
        document.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content")?.let { fixUrl(it, baseUrl) }
            ?: document.selectFirst(".poster img, .thumb img, .thumbnail img, .entry-content img, article img, img")?.imageUrl(baseUrl)
            ?: document.body().styleImage(baseUrl)

    private fun hasNextPage(document: Document, page: Int): Boolean =
        document.select("a.next, .next a, a[rel=next], .pagination a, .nav-links a")
            .any { link -> cleanText(link.text()).contains("next", true) || link.attr("href").contains("/page/${page + 1}") }

    private fun inferType(url: String, title: String, text: String, episodeCount: Int): TvType {
        val haystack = "$url $title $text".lowercase(Locale.ROOT)
        return when {
            episodeCount > 0 -> TvType.TvSeries
            listOf("/series", "/tv", "drama", "episode", "season", "eps").any { haystack.contains(it) } -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun shouldFollow(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.") }.getOrDefault("")
        val ownHost = URI(mainUrl).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        return host == ownHost || host == "byseqekaho.com" || host.endsWith("owphbf24.com") || url.shouldFetchEmbedPage()
    }

    private fun String.isMuviproPlayerCandidate(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains("byseqekaho.com/e/") || lower.contains("q8y5z") || lower.contains("owphbf24.com")
    }

    private fun String.isExtractorTerminalHost(): Boolean {
        val host = runCatching { URI(this).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.") }.getOrDefault("")
        return host.contains("q8y5z")
    }

    private fun String.isPlaybackCandidate(): Boolean {
        val lower = lowercase(Locale.ROOT)
        if (isNoiseUrl() || isTrailerOrSocialUrl()) return false
        if (!lower.startsWith("http://") && !lower.startsWith("https://") && !lower.startsWith("//")) return false
        return isPlayableMedia() ||
            isMuviproPlayerCandidate() ||
            lower.contains("drive.google.com") ||
            lower.contains("docs.google.com") ||
            lower.contains("googleusercontent.com") ||
            lower.contains("/embed/") ||
            lower.contains("/player/") ||
            lower.contains("/e/") ||
            lower.contains("/v/") ||
            lower.contains("/video") ||
            lower.contains("/watch") ||
            lower.contains("/play") ||
            lower.contains("/stream") ||
            lower.contains("/file") ||
            lower.contains("/iframe") ||
            lower.contains("/d/")
    }

    private fun String.shouldFetchEmbedPage(): Boolean {
        if (isExtractorTerminalHost()) return false
        val lower = lowercase(Locale.ROOT)
        val host = runCatching { URI(this).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.") }.getOrDefault("")
        val ownHost = URI(mainUrl).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        return lower.contains("/embed/") ||
            lower.contains("/player/") ||
            lower.contains("/e/") ||
            lower.contains("/v/") ||
            lower.contains("/video") ||
            lower.contains("/watch") ||
            lower.contains("/play") ||
            lower.contains("/stream") ||
            lower.contains("/file") ||
            lower.contains("/iframe") ||
            lower.contains("/d/") ||
            (host.isNotBlank() && host != ownHost)
    }

    private fun String.isTrailerOrSocialUrl(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return listOf(
            "youtube.com", "youtu.be", "youtube-nocookie.com",
            "facebook.com", "twitter.com", "x.com/", "instagram.com",
            "tiktok.com", "telegram", "whatsapp"
        ).any { lower.contains(it) }
    }

    private fun playerSourceName(url: String): String {
        val host = runCatching { URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
        return when {
            host.contains("byseqekaho", true) -> "Byse"
            host.contains("q8y5z", true) -> "q8y5z"
            host.contains("sprintcdn", true) || host.contains("owphbf24", true) -> "Byse HLS"
            host.isNotBlank() -> host
            else -> name
        }
    }

    private fun sourceHeaders(url: String, referer: String): Map<String, String> = buildMap {
        put("User-Agent", USER_AGENT)
        put("Accept", "*/*")
        put("Referer", referer)
        put("Origin", originOf(referer))
        if (url.contains("167.71.211.231")) put("Host", "167.71.211.231")
    }

    private fun originOf(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
    }.getOrDefault(mainUrl)

    private fun String.unwrapAjaxHtml(): String {
        val raw = trim().normalizeEscapes()
        if (raw.isBlank() || raw == "0" || raw.equals("false", true) || raw.equals("null", true)) return ""
        val chunks = linkedSetOf(raw)
        Regex("""(?i)\"(?:html|data|content|player|embed|iframe|url)\"\s*:\s*\"((?:\\.|[^\"\\])*)\"""").findAll(raw)
            .map { it.groupValues[1].normalizeEscapes() }
            .filter { it.isNotBlank() && it != "0" }
            .forEach { chunks.add(it) }
        Regex("""(?i)<iframe[^>]+src=['"]([^'"]+)['"]""").findAll(raw)
            .map { it.value.normalizeEscapes() }
            .forEach { chunks.add(it) }
        return chunks.joinToString("\n")
    }

    private fun decodePossibleUrl(raw: String, baseUrl: String): String? =
        decodePossibleUrls(raw, baseUrl).firstOrNull()

    private fun decodePossibleUrls(raw: String, baseUrl: String): List<String> {
        val normalized = raw.normalizeEscapes().trim()
        if (normalized.isBlank()) return emptyList()
        val decodedUrl = runCatching { URLDecoder.decode(normalized, "UTF-8") }.getOrDefault(normalized)
        val decodedBase64 = decodeBase64(normalized)?.normalizeEscapes()
        val candidates = listOfNotNull(normalized, decodedUrl, decodedBase64)
        val links = linkedSetOf<String>()
        candidates.forEach { candidate ->
            fixUrl(candidate, baseUrl)?.takeIf { it.isPlaybackCandidate() }?.let { links.add(it) }
            Regex("""(?i)(?:https?:)?//[^\s'"<>]+""").findAll(candidate)
                .mapNotNull { fixUrl(it.value, baseUrl) }
                .filter { it.isPlaybackCandidate() }
                .forEach { links.add(it) }
        }
        return links.toList()
    }

    private fun decodeBase64(value: String): String? {
        val clean = value.trim().replace(Regex("""\s+"""), "")
        if (clean.length < 8 || clean.any { it == '<' || it == '>' }) return null
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return runCatching { String(Base64.getDecoder().decode(padded), Charsets.UTF_8) }
            .getOrElse { runCatching { String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8) }.getOrNull() }
    }

    private fun Element.imageUrl(baseUrl: String): String? {
        val raw = attr("data-src").ifBlank { attr("data-original").ifBlank { attr("data-lazy-src").ifBlank { attr("src") } } }
            .ifBlank { attr("srcset").substringBefore(" ") }
        return fixUrl(raw, baseUrl)
    }

    private fun Element.styleImage(baseUrl: String): String? =
        Regex("""url\(['"]?([^'")]+)['"]?\)""").find(attr("style"))?.groupValues?.getOrNull(1)?.let { fixUrl(it, baseUrl) }

    private fun isContentUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (!lower.startsWith(mainUrl.lowercase(Locale.ROOT))) return false
        if (BAD_URL_PARTS.any { lower.contains(it) }) return false
        return !lower.endsWith(".jpg") && !lower.endsWith(".png") && !lower.endsWith(".webp") && !lower.endsWith(".gif")
    }

    private fun isUsefulTitle(value: String?): Boolean {
        val title = cleanText(value)
        if (title.length < 2) return false
        val lower = title.lowercase(Locale.ROOT)
        return lower !in setOf("home", "beranda", "nonton", "download", "play", "trailer", "more", "selengkapnya")
    }

    private fun titleFromUrl(url: String): String = url.substringBeforeLast('/').substringAfterLast('/')
        .replace('-', ' ')
        .replace('_', ' ')
        .trim()
        .split(" ")
        .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() } }

    private fun cleanTitle(value: String?): String = cleanText(value)
        .replace(Regex("""(?i)\s*[-|]\s*(CGVIndo|Nonton|Streaming).*$"""), "")
        .replace(Regex("""(?i)^nonton\s+"""), "")
        .trim()

    private fun cleanDescription(value: String?): String? = cleanText(value)
        .takeIf { it.length >= 12 }

    private fun cleanText(value: String?): String = value.orEmpty()
        .replace('\u00a0', ' ')
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun String.slug(): String = lowercase(Locale.ROOT)
        .replace(Regex("""[^a-z0-9]+"""), "-")
        .trim('-')

    private fun String.normalKey(): String = trim().trimEnd('/').lowercase(Locale.ROOT)

    private fun String.normalizeEscapes(): String {
        val basic = this
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&quot;", "\"")
            .replace("\\\"", "\"")
            .replace("&#039;", "'")
        return Regex("""\\u([0-9a-fA-F]{4})""").replace(basic) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
    }

    private fun String.isM3u8(): Boolean = contains(".m3u8", ignoreCase = true)

    private fun String.isPlayableMedia(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            lower.contains(".webm") ||
            lower.contains(".m4v") ||
            lower.contains(".mov") ||
            lower.contains("/hls/")
    }

    private fun String.isNoiseUrl(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return BAD_MEDIA_PARTS.any { lower.contains(it) } || lower.endsWith(".css") || lower.endsWith(".js") || lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".gif") || lower.endsWith(".webp")
    }

    private fun qualityFromUrl(url: String): Int = when {
        url.contains("2160") || url.contains("4k", true) -> Qualities.P2160.value
        url.contains("1440") -> Qualities.P1440.value
        url.contains("1080") -> Qualities.P1080.value
        url.contains("720") -> Qualities.P720.value
        url.contains("480") -> Qualities.P480.value
        url.contains("360") -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    companion object {
        private const val CARD_SELECTOR = "article.item-infinite, article.item, article.has-post-thumbnail, .gmr-box-content, .post, .movie, .film, .item, .card, .result, .thumbnail, .ml-item, .grid-item"
        private const val PLAYER_CONTAINER_SELECTOR = ".muvipro-player, .muvipro-tabs, .player, .server, .server-list, .mirror, .mobius, .embed, .video-content, .gmr-embed-responsive, [id*=player], [class*=player], [class*=server]"
        private const val SERVER_VALUE_SELECTOR = "select option[value], .mobius option[value], .mirror option[value], .server option[value], .server-list option[value], [data-video], [data-embed], [data-url], [data-link], [data-src], [data-player], [data-iframe], [value]"
        private const val PLAYER_LINK_SELECTOR = "iframe[src], iframe[data-src], iframe[data-litespeed-src], embed[src], video[src], video source[src], source[src], a[href*='embed'], a[href*='player'], a[href*='/e/'], a[href*='/v/'], a[href*='/video'], a[href*='.m3u8'], a[href*='.mp4'], a[href*='/hls/'], a[href*='/stream/']"
        private val SERVER_VALUE_ATTRS = listOf("value", "data-video", "data-embed", "data-url", "data-link", "data-src", "data-player", "data-iframe", "data-litespeed-src", "src", "href")
        private val BAD_URL_PARTS = listOf("/wp-content/", "/category/", "/genre/", "/tag/", "/author/", "/page/", "/quality/", "/country/", "/director/", "/cast/", "/actor/", "#", "javascript:", "mailto:", "api.whatsapp.com", "t.me/share")
        private val BAD_MEDIA_PARTS = listOf("doubleclick", "googlesyndication", "google-analytics", "facebook.com", "twitter.com", "telegram", "whatsapp", "/ads", "banner")
    }
}
