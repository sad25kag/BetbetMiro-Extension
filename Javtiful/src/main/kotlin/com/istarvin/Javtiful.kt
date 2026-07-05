package com.istarvin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

class Javtiful : MainAPI() {
    private val jsonMapper = jacksonObjectMapper()

    override var mainUrl = "https://javtiful.com"
    override var name = "Javtiful"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/videos" to "Newest",
        "${mainUrl}/videos?sort=most_viewed" to "Most Viewed",
        "${mainUrl}/videos?sort=top_rated" to "Top Rated",
        "${mainUrl}/videos?sort=top_favorites" to "Top Favorites",
        "${mainUrl}/videos?sort=being_watched" to "Being Watched",
        "${mainUrl}/censored" to "Censored",
        "${mainUrl}/uncensored" to "Uncensored",
        "${mainUrl}/category/female-investigator" to "Female Investigator",
        "${mainUrl}/category/chinese-av" to "Chinese AV",
        "${mainUrl}/category/female-boss" to "Female Boss",
        "${mainUrl}/category/mature-woman" to "Mature Woman",
        "${mainUrl}/category/cosplay" to "Cosplay",
        "${mainUrl}/category/amateur" to "Amateur",
        "${mainUrl}/category/housekeeper" to "Housekeeper",
        "${mainUrl}/category/nurse" to "Nurse",
        "${mainUrl}/category/female-student" to "Female Student",
        "${mainUrl}/category/school-girls" to "School Girls",
        "${mainUrl}/category/office-lady" to "Office Lady",
        "${mainUrl}/category/sister-in-law" to "Sister-in-law",
        "${mainUrl}/category/hypnosis" to "Hypnosis",
        "${mainUrl}/category/beautiful-girl" to "Beautiful Girl",
        "${mainUrl}/category/bbw" to "BBW",
        "${mainUrl}/category/drama" to "Drama",
        "${mainUrl}/category/married-woman" to "Married Woman",
        "${mainUrl}/category/milf" to "Milf",
        "${mainUrl}/category/female-teacher" to "Female Teacher",
        "${mainUrl}/category/affair" to "Affair",
        "${mainUrl}/category/big-tits" to "Big Tits"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?page=$page"
        val res = app.get(url).document
        val home = res.select("article.front-video-card").mapNotNull {
            if (it.classNames().contains("front-partner-card")) return@mapNotNull null
            it.mainPageResults()
        }
        return newHomePageResponse(request.name, home, true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url =
            if (page <= 1) "$mainUrl/search?q=$query" else "$mainUrl/search?page=$page&q=$query"
        val res = app.get(url).document
        val results = res.select("article.front-video-card:not(.front-partner-card)").mapNotNull {
            it.mainPageResults()
        }
        return newSearchResponseList(results, results.isNotEmpty())
    }

    private fun Element.mainPageResults(): SearchResponse? {
        val link = this.selectFirst("a.front-video-title") ?: return null
        val title = link.text().trim()
        val href = fixUrl(link.attr("href"))
        val img = this.selectFirst("img") ?: return null
        val poster = fixUrl(img.attr("data-front-lazy-src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url).document
        val title = res.selectFirst("div.front-watch-title h1")?.text()?.trim() ?: return null
        val poster = res.selectFirst("meta[property=\"og:image\"]")?.attr("content")

        val recommendations =
            res.select("div.front-video-grid-related article.front-video-card:not(.front-ad-card)")
                .mapNotNull {
                    val link = it.selectFirst("a.front-video-title") ?: return@mapNotNull null
                    val recTitle = link.text().trim()
                    val recHref = fixUrl(link.attr("href"))
                    val recPoster = it.selectFirst("img")?.attr("src")

                    newMovieSearchResponse(recTitle, recHref, TvType.NSFW) {
                        this.posterUrl = fixUrlNull(recPoster)
                    }
                }

        val actorsList = res.select("a.front-watch-actor-card").map {
            val name = it.selectFirst("span")?.text()?.trim() ?: ""
            val image = it.selectFirst("img")?.attr("src")?.takeIf { img ->
                img.isNotEmpty() && !img.contains("profile-placeholder.png")
            }
            Actor(name, fixUrlNull(image))
        }

        val dateText =
            res.selectFirst("div.front-watch-detail:contains(Added on) time")?.attr("datetime")
        val year = dateText?.split("-")?.firstOrNull()?.toIntOrNull()
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(poster)
            this.plot =
                res.selectFirst("meta[property=\"og:description\"]")?.attr("content")?.trim()
            this.year = year
            this.tags =
                res.select("div.front-watch-detail:contains(Categories) a, div.front-watch-detail:contains(Tags) a")
                    .map { it.text().trim() }
            this.recommendations = recommendations
            addActors(actorsList)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        val text = response.text
        val document = response.document
        val configRaw = document.selectFirst("script#frontWatchConfig")?.html()
            ?: text.substringAfter("id=\"frontWatchConfig\" type=\"application/json\">", "")
                .substringBefore("</script>", "")
        val configJson = configRaw.cleanupJson()

        val configData = parseWatchConfig(configJson)
        val playerSources = configData?.allSources().orEmpty()
            .ifEmpty { extractPlayerSources(configJson) }
            .filter { !it.resolvedSrc().isNullOrBlank() }
            .distinctBy { it.resolvedSrc() }

        (configData?.resolvedVideoTitle() ?: document.selectFirst("div.front-watch-title h1")?.text())
            ?.substringBefore(" ")
            ?.takeIf { it.contains("-") }
            ?.let { code ->
                getExtractorApiFromName("SubtitleCat").getUrl(
                    url = code,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }

        playerSources.forEach { source ->
            val sourceUrl = source.resolvedSrc()?.normalizeSourceUrl() ?: return@forEach
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    sourceUrl
                ) {
                    this.quality = source.qualityValue()
                    this.referer = "$mainUrl/"
                    this.type = source.linkType(sourceUrl)
                }
            )
        }

        return playerSources.isNotEmpty()
    }

    private fun parseWatchConfig(json: String): WatchConfig? {
        if (json.isBlank()) return null
        return runCatching { jsonMapper.readValue<WatchConfig>(json) }.getOrNull()
    }

    private fun extractPlayerSources(json: String): List<PlayerSource> {
        if (json.isBlank()) return emptyList()
        return Regex("""[\"'](?:src|file)[\"']\s*:\s*[\"']([^\"']+)[\"']""")
            .findAll(json)
            .mapNotNull { match ->
                match.groupValues.getOrNull(1)
                    ?.cleanupJsonString()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { PlayerSource(src = it) }
            }
            .toList()
    }

    private fun String.cleanupJson(): String {
        return Parser.unescapeEntities(this, false)
            .trim()
            .removePrefix("<!--")
            .removeSuffix("-->")
            .replace("\\/", "/")
    }

    private fun String.cleanupJsonString(): String {
        return Parser.unescapeEntities(this, false)
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .trim()
    }

    private fun String.normalizeSourceUrl(): String {
        return when {
            startsWith("//") -> "https:$this"
            startsWith("/") -> fixUrl(this)
            else -> this
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WatchConfig(
        val playerSources: List<PlayerSource>? = null,
        val sources: List<PlayerSource>? = null,
        val videos: List<PlayerSource>? = null,
        val videoTitle: String? = null,
        @JsonProperty("video_title") val videoTitleAlt: String? = null
    ) {
        fun allSources(): List<PlayerSource> = playerSources ?: sources ?: videos ?: emptyList()
        fun resolvedVideoTitle(): String? = videoTitle ?: videoTitleAlt
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PlayerSource(
        val src: String? = null,
        val file: String? = null,
        val type: String? = null,
        val size: Any? = null,
        val label: Any? = null,
        val quality: Any? = null
    ) {
        fun resolvedSrc(): String? = src ?: file

        fun qualityValue(): Int {
            return listOf(size, label, quality)
                .asSequence()
                .mapNotNull { value -> Regex("""(\d{3,4})""").find(value?.toString().orEmpty())?.value?.toIntOrNull() }
                .firstOrNull() ?: Qualities.Unknown.value
        }

        fun linkType(url: String): ExtractorLinkType {
            val text = listOf(type, url).joinToString(" ").lowercase()
            return if (text.contains("mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
        }
    }
}
