package com.sad25kag.aznude

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AZNude : MainAPI() {
    override var mainUrl = "https://www.aznude.com"
    override var name = "AZNude"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val cdnUrl = "https://cdn2.aznude.com"
    private val searchApi = "https://main-aq7es5tiuq-uc.a.run.app/app"
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/browse/videos/trending/1.html" to "Video",
        "${mainUrl}/browse/movies/trending/1.html" to "Movie"
    )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPagedTagUrl(request.data, page)
        val document = app.get(url, headers = browserHeaders).document
        val home = if (request.data.contains("/browse/movies/", true)) {
            document.select("a[href*='/movie/'], div.media-list-item.movie-list-item, div.movie-list-item, div.media-list div.media-list-item, div.media-list-item")
                .mapNotNull { it.toMovieCategoryResult() }
        } else {
            document.select("div.media-list-item.video-list-item, div.video-list-item, div.media-list div.media-list-item")
                .mapNotNull { it.toMainPageResult() }
        }.distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            home,
            hasNext = home.isNotEmpty()
        )
    }

    private fun buildPagedTagUrl(baseUrl: String, page: Int): String {
        val prefix = baseUrl.substringBeforeLast("/")
        return "$prefix/${page.coerceAtLeast(1)}.html"
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val href = selectFirst("a[href]")?.attr("href").absoluteUrl() ?: return null
        val posterUrl = selectFirst("img")?.getImageAttr().absoluteUrl()
        val timeText = selectFirst("span.video-timestamp")?.text()

        if (isTooShort(timeText)) return null
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }


    private fun Element.toMovieCategoryResult(): SearchResponse? {
        val image = selectFirst("img")
        val title = image?.attr("alt")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: image?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("a")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val href = selectFirst("a[href]")?.attr("href").absoluteUrl() ?: return null
        val posterUrl = image?.getImageAttr()?.absoluteUrl()

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (query.isBlank()) return null
        return try {
            val mapper = jacksonObjectMapper().registerKotlinModule()
            val searchToken = app.get(
                "$searchApi/search-token",
                headers = browserHeaders + mapOf("Accept" to "*/*"),
                referer = "$mainUrl/",
            ).text
            val tokenData = mapper.readValue<SearchToken>(searchToken)
            val sid = tokenData.sid.orEmpty()
            val xst = tokenData.token.orEmpty()
            if (sid.isBlank() || xst.isBlank()) return null

            val apiUrl = "$searchApi/exp/initial-search?q=${query.urlEncode()}&page=$page&gender=f&type=null&sortByDate=DESC&sortByViews=views_alltime&dateRange=anytime"
            val jsonString = app.get(
                apiUrl,
                referer = "$mainUrl/",
                headers = browserHeaders + mapOf(
                    "x-sid" to sid,
                    "x-st" to xst,
                    "Accept" to "application/json,text/plain,*/*",
                ),
            ).textLarge

            val searchWrapper: SearchWrapper = mapper.readValue(jsonString)
            val results = buildSearchResults(searchWrapper).distinctBy { it.url }

            newSearchResponseList(
                results,
                hasNext = results.isNotEmpty()
            )
        } catch (e: Exception) {
            Log.e("kraptor_$name", "Search error: ${e.message}")
            null
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1)?.items.orEmpty()

    private fun buildSearchResults(searchWrapper: SearchWrapper): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        searchWrapper.data.celebs
            .filter { it.url.contains("/view/celeb/", true) }
            .forEach { results.addSearchItem(it.text, it.url, it.thumb) }
        searchWrapper.data.movies
            .filter { it.url.contains("/view/movie/", true) }
            .forEach { results.addSearchItem(it.text, it.url, it.thumb) }
        searchWrapper.data.videos
            .filter { it.url.contains("/view/", true) }
            .forEach { results.addSearchItem(it.text, it.url, it.thumb) }
        searchWrapper.data.stories
            .filter { it.url.contains("/view/", true) }
            .forEach { results.addSearchItem(it.text, it.url, it.thumb) }
        return results
    }

    private fun MutableList<SearchResponse>.addSearchItem(title: String, url: String, thumb: String?) {
        val href = url.absoluteUrl() ?: return
        val poster = thumb.toCdnUrl()
        add(
            newMovieSearchResponse(title, href, TvType.NSFW) {
                posterUrl = poster
                posterHeaders = mapOf("Referer" to "$mainUrl/")
            },
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = browserHeaders).document

        if (url.contains("/view/celeb/", true) || url.contains("/view/movie/", true)) {
            val title = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val poster = document.selectFirst("div.single-page-banner_wrapper img, img.single-page-banner")
                ?.getImageAttr()
                .absoluteUrl()
            val score = document.selectFirst("span.rating-score")?.text()
            val tags = document.select("div.col-md-12 h2.video-tags a, h2.video-tags a")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()
            val recommendations = document.select(
                "div.media-list div.media-list-item, div.media-list-item.video-list-item, div.col-lg-2, div.col-lg-3 a.video"
            ).mapNotNull { it.toRecommendationResult() }.distinctBy { it.url }

            val episodes = document.select("div.media-list-item.video-list-item, div.media-list div.media-list-item")
                .mapNotNull { item ->
                    val href = item.selectFirst("a[href]")?.attr("href").absoluteUrl() ?: return@mapNotNull null
                    val episodeTitle = item.selectFirst("img")?.attr("alt")?.trim()
                        ?: item.selectFirst("img")?.attr("title")?.trim()
                        ?: title
                    val posterUrl = item.selectFirst("img")?.getImageAttr().absoluteUrl()
                    val timeText = item.selectFirst("span.video-timestamp, span.play-icon-active2.video-time")?.text()
                    if (isTooShort(timeText)) return@mapNotNull null

                    newEpisode(href) {
                        this.name = episodeTitle
                        this.posterUrl = posterUrl
                    }
                }
                .distinctBy { it.data }

            return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
                this.posterUrl = poster
                this.plot = "$title"
                this.tags = tags
                this.recommendations = recommendations
                score?.let { this.score = Score.from5(it) }
            }
        }

        val title = document.selectFirst("meta[name=title]")?.attr("content")
            ?: document.selectFirst("h1")?.text()
            ?: return null
        val poster = document.selectFirst("link[rel=preload][as=image]")?.attr("href").absoluteUrl()
            ?: document.selectFirst("meta[property=og:image]")?.attr("content").absoluteUrl()
        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
        val tags = document.select("div.col-md-12 h2.video-tags a, h2.video-tags a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val recommendations = document.select("div.col-lg-3 a.video, div.media-list-item.video-list-item, div.media-list div.media-list-item")
            .mapNotNull { it.toRecommendationResult() }
            .distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val anchor = if (tagName().equals("a", true)) this else selectFirst("a[href]") ?: return null
        val title = anchor.selectFirst("img")?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
            ?: anchor.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null
        val href = anchor.attr("href").absoluteUrl() ?: return null
        val posterUrl = anchor.selectFirst("img")?.getImageAttr().absoluteUrl()
        val timeText = anchor.selectFirst("span.play-icon-active2.video-time, span.video-timestamp")?.text()

        if (isTooShort(timeText)) return null
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d("kraptor_$name", "data = $data")
        val document = app.get(data, headers = browserHeaders).document
        val emitted = linkedSetOf<String>()

        suspend fun emitDirect(rawUrl: String?, label: String? = null) {
            val videoUrl = rawUrl?.replace("\\/", "/")?.trim().absoluteUrl() ?: return
            if (!videoUrl.startsWith("http", true)) return
            if (!emitted.add(videoUrl)) return

            val qualityText = label.orEmpty().ifBlank { videoUrl.substringAfterLast('/').substringBefore('?') }
            val qualityValue = qualityFromLabel(qualityText)
            val sourceLabel = if (label.isNullOrBlank()) name else "$name $label"

            if (videoUrl.contains(".m3u8", true)) {
                M3u8Helper.generateM3u8(
                    sourceLabel,
                    videoUrl,
                    referer = "$mainUrl/",
                    headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to USER_AGENT),
                ).forEach(callback)
            } else {
                callback.invoke(
                    newExtractorLink(
                        source = sourceLabel,
                        name = sourceLabel,
                        url = videoUrl,
                        type = INFER_TYPE,
                    ) {
                        this.quality = qualityValue
                        this.referer = "$mainUrl/"
                        this.headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to USER_AGENT)
                    },
                )
            }
        }

        for (source in document.select("video source[src], video[src], source[src]")) {
            emitDirect(source.attr("src"), source.attr("label").ifBlank { source.attr("res") })
        }

        for (anchor in document.select("a[href*='.mp4'], a[href*='.m3u8'], a[href*='.webm']")) {
            emitDirect(anchor.attr("href"), anchor.text())
        }

        for (script in document.select("script")) {
            val scriptContent = script.html().ifBlank { script.data() }

            for (source in extractJwSources(scriptContent)) {
                emitDirect(source.url, source.label)
            }

            for (match in Regex("""(?i)(https?:\\?/\\?/[^"'<>\s]+?\.(?:m3u8|mp4|webm)(?:\?[^"'<>\s]*)?)""").findAll(scriptContent)) {
                emitDirect(match.groupValues[1], null)
            }
        }

        if (emitted.isEmpty()) {
            for (iframe in document.select("iframe[src], iframe[data-src]")) {
                val iframeUrl = iframe.getIframeAttr().absoluteUrl() ?: continue
                try {
                    loadExtractor(iframeUrl, data, subtitleCallback, callback)
                    emitted.add(iframeUrl)
                } catch (_: Exception) {
                    // Skip broken iframe extractor.
                }
            }
        }

        return emitted.isNotEmpty()
    }

    private fun extractJwSources(scriptContent: String): List<VideoSource> {
        val sources = mutableListOf<VideoSource>()
        val blockRegex = Regex("""sources\s*:\s*\[(.*?)]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val block = blockRegex.find(scriptContent)?.groupValues?.getOrNull(1) ?: scriptContent

        val objectRegex = Regex(
            """\{[^{}]*?(?:file|src)\s*:\s*["']([^"']+)["'][^{}]*?(?:label|res)\s*:\s*["']?([^"',}]+)["']?[^{}]*?\} """.trim(),
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        objectRegex.findAll(block).forEach { match ->
            sources += VideoSource(match.groupValues[1], match.groupValues[2])
        }

        val reversedObjectRegex = Regex(
            """\{[^{}]*?(?:label|res)\s*:\s*["']?([^"',}]+)["']?[^{}]*?(?:file|src)\s*:\s*["']([^"']+)["'][^{}]*?\} """.trim(),
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        reversedObjectRegex.findAll(block).forEach { match ->
            sources += VideoSource(match.groupValues[2], match.groupValues[1])
        }

        val simpleFileRegex = Regex("""(?:file|src)\s*:\s*["']([^"']+\.(?:mp4|m3u8|webm)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE)
        simpleFileRegex.findAll(block).forEach { match ->
            sources += VideoSource(match.groupValues[1], null)
        }

        return sources.distinctBy { it.url }
    }

    private fun qualityFromLabel(raw: String): Int {
        val label = raw.uppercase()
        return when {
            label.contains("2160") || label.contains("4K") -> Qualities.P2160.value
            label.contains("1080") || label.contains("FHD") -> Qualities.P1080.value
            label.contains("720") || label.contains("HD") -> Qualities.P720.value
            label.contains("480") || label.contains("HQ") -> Qualities.P480.value
            label.contains("360") -> Qualities.P360.value
            label.contains("240") || label.contains("LQ") -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    private fun isTooShort(timeText: String?): Boolean {
        return timeText?.trim()?.matches(Regex("^00:(?:[0-1]\\d|20)$")) == true
    }

    private fun String?.absoluteUrl(): String? {
        val value = this?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", true) } ?: return null
        return when {
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> fixUrlNull(value)
        }
    }

    private fun String?.toCdnUrl(): String? {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            value.startsWith("http", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$cdnUrl$value"
            else -> "$cdnUrl/$value"
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true }
            ?: this?.attr("data-src").takeIf { it?.isNotEmpty() == true }
            ?: this?.attr("src")
    }

    private fun Element?.getImageAttr(): String? {
        return when {
            this == null -> null
            hasAttr("data-src") -> attr("data-src")
            hasAttr("data-lazy-src") -> attr("data-lazy-src")
            hasAttr("srcset") -> attr("srcset").substringBefore(" ")
            else -> attr("src")
        }
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")

    private data class VideoSource(
        val url: String,
        val label: String?,
    )
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchWrapper(
    val count: Count? = null,
    val data: Data = Data(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Count(
    val celebs: Int? = 0,
    val movies: Int? = 0,
    val stories: Int? = 0,
    val videos: Int? = 0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Data(
    val celebs: List<AZNudeActor> = emptyList(),
    val movies: List<Movies> = emptyList(),
    val stories: List<Story> = emptyList(),
    val videos: List<Video> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Movies(
    val text: String,
    val thumb: String? = null,
    val url: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Video(
    val text: String,
    val thumb: String? = null,
    val url: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AZNudeActor(
    val text: String,
    val thumb: String? = null,
    val url: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Story(
    val text: String,
    val thumb: String? = null,
    val url: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchToken(
    val token: String? = null,
    val exp: String? = null,
    val sid: String? = null,
)
