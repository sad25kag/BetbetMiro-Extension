package com.sad25kag.dramaserial

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

class DramaSerialProvider : MainAPI() {
    companion object {
        const val DEFAULT_MAIN_URL = "https://tv1.dramaserial.asia"
    }

    override var mainUrl = DEFAULT_MAIN_URL
    override var name = "DramaSerial"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/Genre/ongoing/" to "Drama Sedang Tayang",
        "$mainUrl/Genre/drama-serial-korea/" to "Drama Serial Korea",
        "$mainUrl/Genre/drama-serial-mandarin/" to "Drama Serial Mandarin",
        "$mainUrl/Genre/drama-serial-jepang/" to "Drama Serial Jepang",
        "$mainUrl/Genre/drama-serial-barat/" to "Drama Serial Barat",
        "$mainUrl/Genre/drama-serial-thailand/" to "Drama Serial Thailand",
        "$mainUrl/Genre/drama-serial-india/" to "Drama Serial India",
        "$mainUrl/Genre/drama-serial-filipina/" to "Drama Serial Filipina",
        "$mainUrl/Genre/box-office/" to "Film Box Office",
        "$mainUrl/" to "Latest Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.toPageUrl(page)
        val document = DramaSerialNetwork.getDocument(url, mainUrl)
        val defaultType = when {
            request.data.contains("box-office", ignoreCase = true) -> TvType.Movie
            else -> TvType.AsianDrama
        }
        val items = DramaSerialParser.parseHomeItems(this, document, url, defaultType)
            .distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = DramaSerialParser.hasNextPage(document, page)
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = query.urlEncodeDs()
        val url = "$mainUrl/?s=$encoded"
        val document = DramaSerialNetwork.getDocument(url, mainUrl)
        return DramaSerialParser.parseHomeItems(this, document, url, TvType.AsianDrama)
            .filter { it.name.contains(query, ignoreCase = true) || query.length <= 4 }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = DramaSerialNetwork.getDocument(url, mainUrl)
        val title = DramaSerialParser.parseTitle(document).ifBlank { return null }
        val poster = DramaSerialParser.parsePoster(document, url)
        val plot = DramaSerialParser.parsePlot(document)
        val tags = DramaSerialParser.parseTags(document)
        val year = DramaSerialParser.parseYear(document, title)
        val type = DramaSerialParser.inferType(title, url, TvType.AsianDrama)
        val episodes = DramaSerialParser.parseEpisodes(this, document, url)

        return when {
            episodes.isNotEmpty() -> {
                newTvSeriesLoadResponse(title, url, type, episodes) {
                    posterUrl = poster
                    this.plot = plot
                    this.tags = tags
                    this.year = year
                }
            }
            // /film-seri/ root pages that have only episode 1 (the current page itself, no
            // numbered sub-links found) — still return a TvSeries with a single episode.
            url.contains("/film-seri/", ignoreCase = true) && type != TvType.Movie -> {
                val ep1 = newEpisode(url) {
                    name = "Episode 1"
                    episode = 1
                }
                newTvSeriesLoadResponse(title, url, type, listOf(ep1)) {
                    posterUrl = poster
                    this.plot = plot
                    this.tags = tags
                    this.year = year
                }
            }
            else -> {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    posterUrl = poster
                    this.plot = plot
                    this.tags = tags
                    this.year = year
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return DramaSerialExtractor.load(data, subtitleCallback, callback)
    }

    private fun String.toPageUrl(page: Int): String {
        val base = trimEnd('/')
        return if (page <= 1) "$base/" else "$base/page/$page/"
    }
}
