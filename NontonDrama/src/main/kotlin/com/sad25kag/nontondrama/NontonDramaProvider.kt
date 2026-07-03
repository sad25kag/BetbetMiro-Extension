package com.sad25kag.nontondrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

class NontonDramaProvider : MainAPI() {
    companion object {
        const val DEFAULT_MAIN_URL = "https://nontondrama.blog"
    }

    override var mainUrl = DEFAULT_MAIN_URL
    override var name = "NontonDrama"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Update Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.toPageUrl(page)
        val document = NontonDramaNetwork.getDocument(url, mainUrl)
        val items = NontonDramaParser.parseHomeItems(this, document, url, TvType.AsianDrama)
            .distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = NontonDramaParser.hasNextPage(document, page)
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = query.urlEncodeNd()
        val url = "$mainUrl/?s=$encoded"
        val document = NontonDramaNetwork.getDocument(url, mainUrl)
        return NontonDramaParser.parseHomeItems(this, document, url, TvType.AsianDrama)
            .filter { it.name.contains(query, ignoreCase = true) || query.length <= 4 }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = NontonDramaNetwork.getDocument(url, mainUrl)
        val title = NontonDramaParser.parseTitle(document).ifBlank { return null }
        val poster = NontonDramaParser.parsePoster(document, url)
        val plot = NontonDramaParser.parsePlot(document)
        val tags = NontonDramaParser.parseTags(document)
        val year = NontonDramaParser.parseYear(document, title)
        val type = NontonDramaParser.inferType(title, url, TvType.AsianDrama)
        val episodes = NontonDramaParser.parseEpisodes(this, document, url)

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, type, episodes) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.AsianDrama, url) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return NontonDramaExtractor.load(data, subtitleCallback, callback)
    }

    private fun String.toPageUrl(page: Int): String {
        val base = trimEnd('/')
        return if (page <= 1) "$base/" else "$base/page/$page/"
    }
}
