package com.sad25kag.drakorasia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DrakorAsiaProvider : MainAPI() {
    override var mainUrl = "https://www.drakorasia.site"
    override var name = "DrakorAsia"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 100L

    private val browserHeaders = mapOf(
        "User-Agent" to DrakorAsiaExtractor.ANDROID_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "Ongoing" to "Ongoing",
        "Completed" to "Completed",
        "Movie" to "Movie",
        "Action" to "Action",
        "Comedy" to "Comedy",
        "Drama" to "Drama",
        "Fantasy" to "Fantasy",
        "Romance" to "Romance",
        "Thriller" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cards = fetchFeedCards(request.data, page, includeEpisodes = true).take(36)
        if (cards.isEmpty()) throw ErrorLoadingException("DrakorAsia: no cards parsed from ${request.name}")
        return newHomePageResponse(
            HomePageList(request.name, cards),
            hasNext = cards.size >= FEED_PAGE_SIZE
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val searchUrls = listOf(
            "$mainUrl/search?q=$encoded",
            "$mainUrl/?q=$encoded",
            "$mainUrl/search/label/$encoded"
        )

        for (url in searchUrls) {
            val results = runCatching {
                val document = app.get(url, referer = mainUrl, headers = browserHeaders).document
                parseHtmlCards(document, includeEpisodes = true)
                    .filter { it.name.contains(cleanQuery, ignoreCase = true) }
                    .take(60)
            }.getOrNull().orEmpty()
            if (results.isNotEmpty()) return results.distinctBy { it.url }
        }

        return fetchFeedCards(cleanQuery, 1, includeEpisodes = true)
            .filter { it.name.contains(cleanQuery, ignoreCase = true) }
            .distinctBy { it.url }
            .take(60)
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = absoluteUrl(url) ?: return null
        val document = app.get(cleanUrl, referer = mainUrl, headers = browserHeaders).document
        val title = cleanTitle(
            document.selectFirst("h1.entry-title, h1.post-title, h1.title-post, h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: cleanUrl.substringAfterLast('/').substringBeforeLast('.').replace('-', ' ')
        ).ifBlank { return null }

        val fullText = document.text()
        val labels = parseLabels(document)
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { absoluteUrl(it) }
            ?: document.selectFirst(".bigcontent img, article img, .post img, .separator img")?.imageUrl()
        val plot = parsePlot(document, title)
        val tags = labels.take(20)
        val year = extractYear(fullText) ?: extractYear(title)
        val rating = parseRating(document, fullText)
        val status = parseStatus(fullText, labels)
        val isMovie = document.select("a[href*='/search/label/Movie']").isNotEmpty() || fullText.contains("Type Movie", true)
        val recommendations = parseHtmlCards(document, includeEpisodes = false).filterNot { it.url == cleanUrl }.take(16)

        return if (isMovie) {
            val moviePlayData = findMoviePlayData(document, cleanUrl, title)
            newMovieLoadResponse(title, cleanUrl, TvType.Movie, moviePlayData) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                if (rating != null) addScore(rating.toString(), 10)
                this.recommendations = recommendations
            }
        } else {
            val hasPlayable = hasPlayerEvidence(document)
            val seriesLabel = labels.firstOrNull { it.equals(title, true) } ?: title
            val detailEpisodes = parseDetailEpisodes(document, cleanUrl, title)
            val feedEpisodes = fetchEpisodes(seriesLabel, title)
            val episodes = (detailEpisodes + feedEpisodes)
                .distinctBy { it.data.substringBefore('?').trimEnd('/') }
                .sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name })
                .ifEmpty {
                    if (hasPlayable || isEpisodeTitle(title)) {
                        listOf(
                            newEpisode(cleanUrl) {
                                name = "Episode 1"
                                episode = 1
                                posterUrl = poster
                            }
                        )
                    } else {
                        emptyList()
                    }
                }

            newTvSeriesLoadResponse(title, cleanUrl, TvType.AsianDrama, episodes) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                if (status != null) showStatus = status
                if (rating != null) addScore(rating.toString(), 10)
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
        return DrakorAsiaExtractor.resolve(
            pageUrl = absoluteUrl(data) ?: data,
            mainUrl = mainUrl,
            pageHeaders = browserHeaders,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    private suspend fun fetchFeedCards(label: String, page: Int, includeEpisodes: Boolean): List<SearchResponse> {
        val response = runCatching {
            app.get(feedUrl(label, page), referer = mainUrl, headers = browserHeaders).text
        }.getOrNull() ?: return emptyList()

        return parseFeedEntries(response).mapNotNull { entry ->
            val url = absoluteUrl(entry.url) ?: return@mapNotNull null
            if (!isContentUrl(url)) return@mapNotNull null
            val title = cleanTitle(entry.title).ifBlank { return@mapNotNull null }
            if (!includeEpisodes && isEpisodeTitle(title)) return@mapNotNull null

            val doc = Jsoup.parse(entry.content, url)
            val text = doc.text()
            val poster = entry.thumbnail ?: doc.selectFirst("img")?.imageUrl()
            val year = extractYear(text) ?: extractYear(title)
            val isMovie = entry.labels.any { it.equals("Movie", true) } || text.contains("Type Movie", true)

            if (isMovie && !isEpisodeTitle(title)) {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    posterUrl = poster
                    this.year = year
                }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.AsianDrama) {
                    posterUrl = poster
                    this.year = year
                }
            }
        }.distinctBy { it.url.substringBefore('?').trimEnd('/') }
    }

    private suspend fun fetchEpisodes(seriesLabel: String, seriesTitle: String): List<Episode> {
        val entries = runCatching {
            parseFeedEntries(app.get(feedUrl(seriesLabel, 1, maxResults = 150), referer = mainUrl, headers = browserHeaders).text)
        }.getOrNull().orEmpty()

        return entriesToEpisodes(entries, seriesTitle)
    }

    private suspend fun fetchSearchEpisodes(query: String, seriesTitle: String): List<Episode> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val entries = runCatching {
            parseFeedEntries(app.get("$mainUrl/feeds/posts/default?alt=json&q=$encoded&max-results=150", referer = mainUrl, headers = browserHeaders).text)
        }.getOrNull().orEmpty()

        return entriesToEpisodes(entries, seriesTitle)
    }

    private fun entriesToEpisodes(entries: List<FeedEntry>, seriesTitle: String): List<Episode> {
        val baseTitle = normalizedSeriesTitle(seriesTitle)
        return entries
            .filter { entry ->
                val entryTitle = cleanTitle(entry.title)
                isEpisodeTitle(entryTitle) && normalizedSeriesTitle(entryTitle).contains(baseTitle, ignoreCase = true)
            }
            .mapNotNull { entry ->
                val epUrl = absoluteUrl(entry.url) ?: return@mapNotNull null
                val epTitle = cleanTitle(entry.title)
                val ep = episodeNumber(epTitle)
                newEpisode(epUrl) {
                    name = ep?.let { "Episode $it" } ?: epTitle
                    episode = ep
                }
            }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name })
    }

    private suspend fun findMoviePlayData(document: Document, currentUrl: String, title: String): String {
        return (parseDetailEpisodes(document, currentUrl, title) + fetchEpisodes(title, title) + fetchSearchEpisodes(title, title))
            .distinctBy { it.data.substringBefore('?').trimEnd('/') }
            .sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name })
            .firstOrNull()
            ?.data
            ?: currentUrl
    }

    private fun parseDetailEpisodes(document: Document, currentUrl: String, seriesTitle: String): List<Episode> {
        val baseTitle = normalizedSeriesTitle(seriesTitle)
        val scopedAnchors = listOf(
            ".episode-list a[href]",
            ".episodelist a[href]",
            ".eplister a[href]",
            ".eps a[href]",
            ".bixbox a[href]",
            ".post-body a[href]",
            ".entry-content a[href]",
            "article a[href]",
            "main a[href]"
        ).flatMap { selector -> document.select(selector) }

        val anchors = scopedAnchors.ifEmpty { document.select("a[href*='.html']").toList() }
        return anchors.mapNotNull { anchor ->
            val href = absoluteUrl(anchor.attr("href")) ?: return@mapNotNull null
            val normalizedHref = href.substringBefore('?').trimEnd('/')
            if (normalizedHref == currentUrl.substringBefore('?').trimEnd('/')) return@mapNotNull null
            if (!isContentUrl(href)) return@mapNotNull null

            val text = cleanTitle(
                anchor.text().ifBlank { null }.ifBlank { anchor.attr("title") }
                    .ifBlank { href.substringAfterLast('/').substringBeforeLast('.').replace('-', ' ') }
            )
            val slugTitle = cleanTitle(href.substringAfterLast('/').substringBeforeLast('.').replace('-', ' '))
            val ep = episodeNumber(text) ?: episodeNumber(slugTitle) ?: episodeNumber(href)
            val looksLikeEpisode = ep != null || text.contains("Episode", true) || slugTitle.contains("Episode", true)
            if (!looksLikeEpisode) return@mapNotNull null

            val combinedTitle = normalizedSeriesTitle("$text $slugTitle")
            val shortEpisodeText = Regex("(?i)^episode\\s*0*[0-9]+").containsMatchIn(text)
            if (baseTitle.isNotBlank() && !combinedTitle.contains(baseTitle, ignoreCase = true) && !shortEpisodeText) {
                return@mapNotNull null
            }

            newEpisode(href) {
                name = ep?.let { "Episode $it" } ?: text.ifBlank { slugTitle }
                episode = ep
            }
        }.distinctBy { it.data.substringBefore('?').trimEnd('/') }
            .sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name })
    }

    private fun parseFeedEntries(text: String): List<FeedEntry> {
        val jsonText = stripJsonp(text)
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val entries = root.optJSONObject("feed")?.optJSONArray("entry") ?: return emptyList()
        val result = mutableListOf<FeedEntry>()

        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val title = entry.optJSONObject("title")?.optString("\$t").orEmpty()
            val content = entry.optJSONObject("content")?.optString("\$t").orEmpty()
            val thumbnail = entry.optJSONObject("media\$thumbnail")?.optString("url")?.replace("s72-c", "s400")
            val labels = mutableListOf<String>()
            val categories = entry.optJSONArray("category")
            if (categories != null) {
                for (j in 0 until categories.length()) {
                    categories.optJSONObject(j)?.optString("term")?.takeIf { it.isNotBlank() }?.let(labels::add)
                }
            }

            var href = ""
            val links = entry.optJSONArray("link")
            if (links != null) {
                for (j in 0 until links.length()) {
                    val link = links.optJSONObject(j) ?: continue
                    if (link.optString("rel") == "alternate") {
                        href = link.optString("href")
                        break
                    }
                }
            }

            if (title.isNotBlank() && href.isNotBlank()) {
                result.add(FeedEntry(title, href.cleanEscaped(), content.cleanEscaped(), thumbnail?.cleanEscaped(), labels.distinct()))
            }
        }
        return result
    }

    private fun parseHtmlCards(document: Document, includeEpisodes: Boolean): List<SearchResponse> {
        val containers = document.select(
            "#main-wrapper article, main article, .blog-posts article, .post-outer, article.hentry, article, .post"
        ).ifEmpty { document.select("a[href*='.html']") }

        return containers.mapNotNull { element ->
            parseHtmlCard(element, includeEpisodes)
        }.distinctBy { it.url.substringBefore('?').trimEnd('/') }
    }

    private fun parseHtmlCard(element: Element, includeEpisodes: Boolean): SearchResponse? {
        val anchor = bestContentAnchor(element) ?: return null
        val href = absoluteUrl(anchor.attr("href")) ?: return null
        if (!isContentUrl(href)) return null

        val rawTitle = element.selectFirst("h1 a, h2 a, h3 a, .entry-title a, .post-title a")?.text()
            ?.ifBlank { null }
            ?: anchor.attr("title").ifBlank { null }
            ?: anchor.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: element.selectFirst("h1, h2, h3, .entry-title, .post-title")?.text()?.ifBlank { null }
            ?: anchor.text().ifBlank { null }
            ?: href.substringAfterLast('/').substringBeforeLast('.').replace('-', ' ')

        val title = cleanTitle(rawTitle)
        if (title.isBlank() || isNoiseTitle(title)) return null
        if (!includeEpisodes && isEpisodeTitle(title)) return null

        val text = element.text()
        val poster = (anchor.selectFirst("img") ?: element.selectFirst("img"))?.imageUrl()
        val year = extractYear(text) ?: extractYear(title)
        val isMovie = text.contains("Movie", true) && !isEpisodeTitle(title)

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                posterUrl = poster
                this.year = year
            }
        }
    }

    private fun bestContentAnchor(element: Element): Element? {
        if (element.tagName().equals("a", true) && element.hasAttr("href")) return element
        return element.selectFirst("h1 a[href], h2 a[href], h3 a[href], .entry-title a[href], .post-title a[href]")
            ?: element.selectFirst("a[href*='drakorasia.site'][href*='.html']:has(img)")
            ?: element.selectFirst("a[href*='drakorasia.site'][href*='.html']")
            ?: element.selectFirst("a[href*='.html']:has(img)")
            ?: element.selectFirst("a[href*='.html']")
    }

    private fun parseLabels(document: Document): List<String> {
        val scopedSelectors = listOf(
            ".post-body",
            ".entry-content",
            ".descNime",
            ".post-sinop",
            ".bigcontent",
            "article",
            "main"
        )

        scopedSelectors.forEach { selector ->
            val scoped = document.select(selector)
            val labels = scoped.select("a[href*='/search/label/']")
                .map { cleanText(it.text()).trim(',') }
                .filter { it.length in 2..50 && !isNoiseTitle(it) && !isBlockedDetailLabel(it) }
                .distinct()
            if (labels.isNotEmpty()) return labels
        }

        return document.select("a[href*='/search/label/']")
            .map { cleanText(it.text()).trim(',') }
            .filter { it.length in 2..50 && !isNoiseTitle(it) && !isBlockedDetailLabel(it) }
            .distinct()
    }

    private fun parsePlot(document: Document, title: String): String? {
        val body = document.select(".post-sinop p, .descNime p, .entry-content p, .post-body p, article p, main p")
            .map { sanitizePlot(it.text()) }
            .firstOrNull { isValidPlot(it, title) }
        val meta = document.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")
            ?.let { sanitizePlot(it) }
            ?.takeIf { isValidPlot(it, title) }
        return body ?: meta
    }

    private fun parseRating(document: Document, text: String): Double? {
        val ratingText = document.selectFirst(".rating, .score, .imdb, .site-ret, [class*=rating]")?.text().orEmpty()
            .ifBlank { text }
        return Regex("(?i)(?:rating|score)?\\s*([0-9](?:\\.[0-9])?)\\s*(?:/10)?").find(ratingText)
            ?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }

    private fun parseStatus(text: String, labels: List<String>): ShowStatus? {
        return when {
            labels.any { it.equals("Ongoing", true) } || text.contains("Status Ongoing", true) -> ShowStatus.Ongoing
            labels.any { it.equals("Completed", true) } || text.contains("Status Completed", true) -> ShowStatus.Completed
            else -> null
        }
    }

    private fun hasPlayerEvidence(document: Document): Boolean {
        val html = document.outerHtml()
        return document.select("select.selectServ option[value], #selectServ option[value], a.download[href], a[href*='/video/down.php']").isNotEmpty()
            || html.contains("abyssplayer.com", true)
            || html.contains("/video/down.php", true)
    }

    private fun feedUrl(label: String, page: Int, maxResults: Int = FEED_PAGE_SIZE): String {
        val start = ((page - 1).coerceAtLeast(0) * maxResults) + 1
        return if (label.isBlank()) {
            "$mainUrl/feeds/posts/default?alt=json&start-index=$start&max-results=$maxResults&orderby=updated"
        } else {
            val encoded = URLEncoder.encode(label, "UTF-8").replace("+", "%20")
            "$mainUrl/feeds/posts/default/-/$encoded?alt=json&start-index=$start&max-results=$maxResults"
        }
    }

    private fun stripJsonp(text: String): String {
        val clean = text.trim().removePrefix("// API callback").trim()
        if (clean.startsWith("{") && clean.endsWith("}")) return clean
        return clean.substringAfter('(', clean).substringBeforeLast(')', clean).trim()
    }

    private fun absoluteUrl(url: String?): String? {
        val raw = url?.trim()?.cleanEscaped().orEmpty()
        if (raw.isBlank() || raw.startsWith("javascript:", true)) return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http", true) -> raw
            raw.startsWith("/") -> "$mainUrl$raw"
            else -> "$mainUrl/$raw"
        }
    }

    private fun Element.imageUrl(): String? {
        val raw = when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            hasAttr("src") -> attr("abs:src")
            else -> ""
        }
        return absoluteUrl(raw)
    }

    private fun isContentUrl(url: String): Boolean {
        return url.contains("drakorasia.site", true) && Regex("/20\\d{2}/\\d{2}/[^?#]+\\.html").containsMatchIn(url)
    }

    private fun normalizedSeriesTitle(value: String): String {
        return cleanTitle(value)
            .replace(Regex("(?i)\\b(ep(?:isode)?|eposide)\\s*0*[0-9]+\\b"), "")
            .replace(Regex("(?i)\\bseason\\s*[0-9]+\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanTitle(value: String): String {
        return cleanText(value)
            .substringBefore(" - drakorasia")
            .replace(Regex("(?i)^download\\s+drama\\s+korea\\s+"), "")
            .replace(Regex("(?i)\\s+subtitle\\s+indonesia.*$"), "")
            .replace(Regex("(?i)\\s+sub\\s+indo.*$"), "")
            .replace(Regex("(?i)\\s+full\\s+hd.*$"), "")
            .trim(' ', '-', '|')
    }

    private fun cleanText(value: String): String {
        return value.replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String.cleanEscaped(): String {
        return replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003c", "<")
            .replace("\\u003e", ">")
    }

    private fun isEpisodeTitle(value: String): Boolean {
        return Regex("(?i)\\b(ep(?:isode)?|eposide)\\s*0*[0-9]+\\b").containsMatchIn(value)
    }

    private fun episodeNumber(value: String): Int? {
        return Regex("(?i)\\b(?:ep(?:isode)?|eposide)\\s*0*([0-9]+)\\b").find(value)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractYear(value: String): Int? {
        return Regex("\\b(20[0-3][0-9]|19[0-9]{2})\\b").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun isNoiseTitle(value: String): Boolean {
        val lowered = value.trim().lowercase().trim(',')
        return value.length < 2 || lowered in setOf(
            "home", "genre", "season", "studio", "bookmark", "search", "tonton", "download", "advertise here", "video server", "server 1", "server 2"
        )
    }

    private fun isBlockedDetailLabel(value: String): Boolean {
        val lowered = value.trim().lowercase().trim(',', ' ')
        return lowered in setOf(
            "series", "tv", "movie", "completed", "ongoing", "complete", "0-9", "0–9", "drakorasia", "drama asia"
        )
    }

    private fun isValidPlot(value: String, title: String): Boolean {
        val cleaned = sanitizePlot(value)
        val episodeMentions = Regex("(?i)\\bEpisode\\s*0*[0-9]+\\b").findAll(cleaned).count()
        val subIndoMentions = Regex("(?i)\\bSub\\s*indo\\b").findAll(cleaned).count()
        return cleaned.length > 40 &&
            episodeMentions < 3 &&
            subIndoMentions < 3 &&
            !cleaned.equals(title, ignoreCase = true) &&
            !cleaned.contains("Bookmark", true) &&
            !cleaned.matches(Regex("(?i)^episode\\s*0*[0-9]+.*"))
    }

    private fun sanitizePlot(value: String): String {
        return cleanText(value)
            .replace(Regex("(?i)\\s*Video\\s+Server.*$"), "")
            .replace(Regex("(?i)\\s*Expand\\s+Turn\\s+Off\\s+Light.*$"), "")
            .trim()
    }

    private data class FeedEntry(
        val title: String,
        val url: String,
        val content: String,
        val thumbnail: String?,
        val labels: List<String>
    )

    private companion object {
        const val FEED_PAGE_SIZE = 25
    }
}