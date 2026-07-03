package com.sad25kag.garasifilm21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

class GarasiFilm21Provider : MainAPI() {
    companion object {
        const val DEFAULT_MAIN_URL = "https://tv9.gf21.fun"
    }

    override var mainUrl = DEFAULT_MAIN_URL
    override var name = "GarasiFilm21"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Update Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.toPageUrl(page)
        val document = GarasiFilm21Network.getDocument(url, mainUrl)
        val items = GarasiFilm21Parser.parseHomeItems(this, document, url, TvType.Movie)
            .distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = GarasiFilm21Parser.hasNextPage(document, page)
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = query.urlEncodeGf21()
        val url = "$mainUrl/?s=$encoded"
        val document = GarasiFilm21Network.getDocument(url, mainUrl)
        return GarasiFilm21Parser.parseHomeItems(this, document, url, TvType.Movie)
            .filter { it.name.contains(query, ignoreCase = true) || query.length <= 4 }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = GarasiFilm21Network.getDocument(url, mainUrl)
        val title = GarasiFilm21Parser.parseTitle(document).ifBlank { return null }
        val poster = GarasiFilm21Parser.parsePoster(document, url)
        val plot = GarasiFilm21Parser.parsePlot(document)
        val tags = GarasiFilm21Parser.parseTags(document)
        val year = GarasiFilm21Parser.parseYear(document, title)
        val type = GarasiFilm21Parser.inferType(title, url, TvType.Movie)
        val episodes = GarasiFilm21Parser.parseEpisodes(this, document, url)

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, type, episodes) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
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
        return GarasiFilm21Extractor.load(data, subtitleCallback, callback)
    }

    private fun String.toPageUrl(page: Int): String {
        val base = trimEnd('/')
        return if (page <= 1) "$base/" else "$base/page/$page/"
    }
}
