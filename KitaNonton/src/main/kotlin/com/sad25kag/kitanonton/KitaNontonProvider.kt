package com.kitanonton

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink

class KitaNontonProvider : MainAPI() {
    override var mainUrl = KitaNontonUtils.MAIN_URL
    override var name = "KitaNonton"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(*KitaNontonUtils.mainPage)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = KitaNontonUtils.pageUrl(request.data, page)
        val results = runCatching {
            val document = app.get(url, headers = KitaNontonUtils.siteHeaders(url), referer = mainUrl).document
            KitaNontonParser.parseListing(this, document)
        }.getOrElse { emptyList() }
        return newHomePageResponse(if (results.isNotEmpty()) listOf(HomePageList(request.name, results)) else emptyList())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = KitaNontonUtils.searchUrl(query)
        return runCatching {
            val document = app.get(url, headers = KitaNontonUtils.siteHeaders(url), referer = mainUrl).document
            KitaNontonParser.parseListing(this, document)
        }.getOrElse { emptyList() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        return runCatching {
            val document = app.get(url, headers = KitaNontonUtils.siteHeaders(url), referer = mainUrl).document
            KitaNontonParser.parseLoadResponse(this, url, document)
        }.getOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return KitaNontonExtractor.loadLinks(name, data, subtitleCallback, callback)
    }
}
