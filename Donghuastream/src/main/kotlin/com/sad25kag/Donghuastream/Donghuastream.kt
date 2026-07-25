package com.sad25kag.Donghuastream

import android.content.Context
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

open class Donghuastream : MainAPI() {
    companion object {
        var context: Context? = null
    }

    override var mainUrl = "https://donghuastream.org"
    override var name = "Donghuastream"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?status=&type=&order=update&page={page}" to "Update Terbaru",
        "$mainUrl/anime/?status=completed&type=&order=update&page={page}" to "Completed",
        "$mainUrl/anime/?status=&type=special&order=update&page={page}" to "Special",
        "$mainUrl/genres/adventure/pagg/{page}/" to "Adventure",
        "$mainUrl/genres/another-world/pagg/{page}/" to "Another World",
        "$mainUrl/genres/reincarnated/pagg/{page}/" to "Reincarnated",
        "$mainUrl/genres/romance/pagg/{page}/" to "Romance",
        "$mainUrl/genres/swords-fight/pagg/{page}/" to "Sword Fight"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(resolvePage(request.data, page)).document
        val items = doc.select("div.listupd > article").mapNotNull { card -> card.toCard() }
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = true
        )
    }

    private fun Element.toCard(): SearchResponse? {
        val link = selectFirst("div.bsx > a") ?: return null
        val title = link.attr("title").trim()
        val href = fixUrl(link.attr("href"))
        val image = fixUrlNull(selectFirst("div.bsx a img")?.image())
        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = image
        }
    }

    private fun Element.image(): String {
        return when {
            hasAttr("data-src") -> attr("data-src")
            hasAttr("src") -> attr("src")
            else -> attr("src")
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        for (page in 1..3) {
            val doc = app.get(if (page == 1) "$mainUrl/?s=$query" else "$mainUrl/pagg/$page/?s=$query").document
            val batch = doc.select("div.listupd > article").mapNotNull { it.toCard() }
            if (batch.isEmpty()) break
            if (!out.containsAll(batch)) out.addAll(batch) else break
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val firstPlay = doc.selectFirst(".eplister li > a")?.attr("href").orEmpty()
        var poster = doc.select("div.ime > img").attr("data-src")
        val plot = doc.selectFirst("div.entry-content")?.text()?.trim()
        val typeText = doc.selectFirst(".spe")?.text().orEmpty()
        val isMovie = typeText.contains("Movie")

        return if (!isMovie) {
            val episodePage = doc.selectFirst(".eplister li > a")?.attr("href").orEmpty()
            val episodeDoc = app.get(episodePage).document
            val episodes = episodeDoc.select("div.episodelist > ul > li").map { row ->
                val a = row.select("a")
                val epText = a.select("span").text().substringAfter("-").substringBeforeLast("-")
                val epPoster = row.selectFirst("a img")?.attr("data-src").orEmpty()
                newEpisode(a.attr("href")) {
                    name = epText.replace(title, "", ignoreCase = true)
                    episode = epText.toIntOrNull()
                    posterUrl = epPoster
                }
            }
            if (poster.isEmpty()) poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim().orEmpty()
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
                posterUrl = poster
                this.plot = plot
            }
        } else {
            if (poster.isEmpty()) poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim().orEmpty()
            newMovieLoadResponse(title, url, TvType.Movie, firstPlay) {
                posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val servers = doc.select("option[data-index]")

        servers.amap { server ->
            val encoded = server.attr("value")
            if (encoded.isBlank()) return@amap
            val serverName = server.text().trim()
            val decoded = try {
                base64Decode(encoded)
            } catch (_: Exception) {
                Log.w("Error", "Base64 decode failed: $encoded")
                return@amap
            }

            val iframe = Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")?.let(::httpsify)
            if (iframe.isNullOrBlank()) return@amap

            when {
                iframe.contains("vidmoly") -> {
                    val embedded = iframe.substringAfter("=\"").substringBefore("\"")
                    loadExtractor("http:$embedded", referer = iframe, subtitleCallback, callback)
                }
                iframe.endsWith(".mp4") -> {
                    callback(
                        newExtractorLink(serverName, serverName, url = iframe, INFER_TYPE) {
                            referer = ""
                            quality = getQualityFromName(serverName)
                        }
                    )
                }
                else -> loadExtractor(iframe, referer = iframe, subtitleCallback, callback)
            }
        }

        return true
    }

    private fun resolvePage(raw: String, page: Int): String {
        return raw.replace("{page}", page.toString()).let { if (page <= 1) it.replace("/pagg/1/", "/") else it }
    }
}
