package com.sad25kag.bilibili

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

class BilibiliIndonesiaProvider : MainAPI() {
    private val source = BilibiliProvider()

    override var mainUrl = source.mainUrl
    override var name = source.name
    override val hasMainPage = true
    override val hasQuickSearch = source.hasQuickSearch
    override var lang = source.lang
    override val hasDownloadSupport = source.hasDownloadSupport
    override val supportedTypes = source.supportedTypes

    override val mainPage = mainPageOf(
        "sub indonesia" to "Rekomendasi",
        "sub indonesia terbaru" to "Update Terbaru",

        // Kategori umum - label tetap, query dipresisikan ke konten Indonesia.
        "anime sub indonesia" to "Anime",
        "movie sub indonesia" to "Film",
        "drama sub indonesia" to "Drama",
        "series sub indonesia" to "Serial",
        "documentary sub indonesia" to "Dokumenter",
        "short film sub indonesia" to "Film Pendek",

        // Genre - label tetap, query dipresisikan ke sub Indonesia.
        "action anime sub indonesia" to "Aksi",
        "adventure anime sub indonesia" to "Petualangan",
        "comedy anime sub indonesia" to "Komedi",
        "romance anime sub indonesia" to "Romantis",
        "drama anime sub indonesia" to "Drama Anime",
        "fantasy anime sub indonesia" to "Fantasi",
        "isekai anime sub indonesia" to "Isekai",
        "sci-fi anime sub indonesia" to "Fiksi Ilmiah",
        "thriller anime sub indonesia" to "Thriller",
        "horror anime sub indonesia" to "Horor",
        "mystery anime sub indonesia" to "Misteri",
        "slice of life anime sub indonesia" to "Slice of Life",
        "school anime sub indonesia" to "Sekolah",
        "sports anime sub indonesia" to "Olahraga",
        "music anime sub indonesia" to "Musik",
        "supernatural anime sub indonesia" to "Supernatural",

        // Dub / bahasa populer di Bilibili TV.
        "anime dub indonesia" to "Dub Indonesia",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val results = source.search(request.data, page)?.items ?: emptyList()
        return newHomePageResponse(
            arrayListOf(HomePageList(request.name, results, isHorizontalImages = true)),
            hasNext = results.isNotEmpty()
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = source.quickSearch(query)

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        return source.search(query, page)
    }

    override suspend fun load(url: String): LoadResponse? = source.load(url)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = source.loadLinks(data, isCasting, subtitleCallback, callback)
}
