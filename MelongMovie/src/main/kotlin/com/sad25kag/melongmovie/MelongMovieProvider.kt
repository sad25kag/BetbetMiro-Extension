package com.sad25kag.melongmovie

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class MelongMovieProvider : MainAPI() {
    override var mainUrl = "http://139.59.189.160"
    override var name = "MelongMovie"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "/" to "Home",
        "/best-rating/" to "Popular",
        "/genre/action/" to "Action",
        "/genre/adventure/" to "Adventure",
        "/genre/animation/" to "Animation",
        "/genre/comedy/" to "Comedy",
        "/genre/crime/" to "Crime",
        "/genre/documentary/" to "Documentary",
        "/genre/drama/" to "Drama",
        "/genre/family/" to "Family",
        "/genre/fantasy/" to "Fantasy",
        "/genre/history/" to "History",
        "/genre/horror/" to "Horror",
        "/genre/music/" to "Music",
        "/genre/mystery/" to "Mystery",
        "/genre/romance/" to "Romance",
        "/genre/science-fiction/" to "Science Fiction",
        "/genre/thriller/" to "Thriller",
        "/genre/war/" to "War",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url, referer = mainUrl, headers = defaultHeaders).document
        val items = document.toSearchResponses()
        val hasNext = document.selectFirst("a.next, a.nextpostslink, .nav-links a.next, .pagination a.next, a:contains(Next)") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", referer = mainUrl, headers = defaultHeaders).document
        return document.toSearchResponses()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = mainUrl, headers = defaultHeaders).document
        val title = document.selectFirst("h1.entry-title, h1")?.ownText()?.cleanTitle()
            ?: document.title().cleanTitle()
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".gmr-movie-data-top img, .gmr-box-content img, article img")?.imageUrl()
        val plot = document.selectFirst(".entry-content.entry-content-single, .entry-content, meta[property=og:description]")
            ?.let { element -> element.attr("content").ifBlank { element.text() } }
            ?.cleanText()
        val year = document.selectFirst(".gmr-moviedata:contains(Tahun)")
            ?.text()
            ?.substringAfter(":", "")
            ?.trim()
            ?.toIntOrNull()
            ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val tags = document.select(".gmr-moviedata:contains(Genre) a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val recommendations = document.toSearchResponses()
            .filterNot { it.url == url }
            .take(12)

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
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
        val iframe = when {
            data.contains("vidhide.org/embed/") -> data
            else -> app.get(data, referer = mainUrl, headers = defaultHeaders)
                .document
                .selectFirst("iframe[src*=vidhide.org/embed], iframe[src*=vidhide]")
                ?.absUrl("src")
        } ?: return false

        // Evidence from HAR: detail page embeds vidhide.org/embed/* with Referer: http://139.59.189.160/.
        // CloudStream extractor resolves the encrypted vidhide -> s3.vidhide.org HLS API flow.
        loadExtractor(iframe, mainUrl, subtitleCallback, callback)
        return true
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val fixedPath = if (path.startsWith("http")) path.removePrefix(mainUrl) else path
        return when {
            page <= 1 -> mainUrl + fixedPath
            fixedPath == "/" -> "$mainUrl/page/$page/"
            fixedPath.endsWith("/") -> "$mainUrl$fixedPath/page/$page/"
            else -> "$mainUrl$fixedPath/page/$page/"
        }
    }

    private fun Document.toSearchResponses(): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        val selectors = listOf(
            "article",
            ".gmr-item-module",
            ".gmr-movie-item",
            ".item",
            ".movie",
            ".post",
            ".hentry",
            ".grid-item",
            ".archive-item"
        )

        selectors.asSequence()
            .flatMap { selector -> select(selector).asSequence() }
            .forEach { element ->
                element.toSearchResponseOrNull()?.let { response ->
                    if (seen.add(response.url)) results.add(response)
                }
            }

        if (results.isNotEmpty()) return results

        select("a[href]").forEach { anchor ->
            if (anchor.selectFirst("img") == null) return@forEach
            anchor.toSearchResponseOrNull()?.let { response ->
                if (seen.add(response.url)) results.add(response)
            }
        }
        return results
    }

    private fun Element.toSearchResponseOrNull(): SearchResponse? {
        val anchor = selectFirst("a[href*=$mainUrl]") ?: selectFirst("a[href]") ?: return null
        val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.normalizeUrl()
        if (!href.isValidDetailUrl()) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")
        val title = image?.attr("alt")?.cleanText()?.takeIf { it.isNotBlank() }
            ?: image?.attr("title")?.cleanText()?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title").cleanText().takeIf { it.isNotBlank() }
            ?: selectFirst("h2, h3, .entry-title, .title")?.text()?.cleanText()?.takeIf { it.isNotBlank() }
            ?: anchor.text().cleanText().takeIf { it.isNotBlank() }
            ?: return null

        val poster = image?.imageUrl()
        return newMovieSearchResponse(title.cleanTitle(), href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    private fun Element.imageUrl(): String? {
        return listOf("data-src", "data-lazy-src", "data-original", "src")
            .firstNotNullOfOrNull { attr -> attr(attr).takeIf { it.isNotBlank() } }
            ?.normalizeUrl()
    }

    private fun String.normalizeUrl(): String {
        return when {
            startsWith("//") -> "http:$this"
            startsWith("/") -> mainUrl + this
            else -> this
        }
    }

    private fun String.isValidDetailUrl(): Boolean {
        if (!startsWith(mainUrl)) return false
        if (contains("/wp-content/") || contains("/wp-admin/") || contains("/wp-json/")) return false
        if (contains("/genre/") || contains("/tag/") || contains("/category/") || contains("/page/")) return false
        if (endsWith(".jpg") || endsWith(".png") || endsWith(".webp") || endsWith(".gif")) return false
        return Regex("$mainUrl/[^/?#]+/($|[?#])").containsMatchIn(this)
    }

    private fun String.cleanTitle(): String {
        return cleanText()
            .replace(Regex("^Melongmovie\\s*-\\s*Melongfilm\\s*-\\s*Nonton Film", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Streaming Online Download$", RegexOption.IGNORE_CASE), "")
            .trim(' ', '-', '|')
    }

    private fun String.cleanText(): String {
        return replace("\u00a0", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private val defaultHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
    }
}
