package com.sad25kag.gomov

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URI
import java.net.URLEncoder
import org.jsoup.nodes.Element

class Gomov : MainAPI() {
    override var mainUrl = "https://gomov.top"
    override var name = "Gomov"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "page/%d/" to "Film Terbaru",
        "genre/action/page/%d/" to "Action",
        "genre/adventure/page/%d/" to "Adventure",
        "genre/comedy/page/%d/" to "Comedy",
        "genre/crime/page/%d/" to "Crime",
        "genre/drama/page/%d/" to "Drama",
        "genre/fantasy/page/%d/" to "Fantasy",
        "genre/history/page/%d/" to "History",
        "genre/mystery/page/%d/" to "Mystery",
        "genre/romance/page/%d/" to "Romance",
        "genre/science-fiction/page/%d/" to "Science Fiction",
        "genre/thriller/page/%d/" to "Thriller",
        "country/usa/page/%d/" to "USA",
        "country/korea/page/%d/" to "Korea",
        "country/china/page/%d/" to "China",
        "country/united-kingdom/page/%d/" to "United Kingdom",
        "year/2016/page/%d/" to "Tahun 2016",
        "year/2015/page/%d/" to "Tahun 2015",
        "year/2014/page/%d/" to "Tahun 2014",
        "year/2009/page/%d/" to "Tahun 2009",
        "year/1994/page/%d/" to "Tahun 1994"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = fixUrl(request.data.format(page))
        val document = app.get(url).document

        val home = document.select(
            "article.item, " +
                "article, " +
                "div.gmr-box-content article, " +
                "div.row article, " +
                "div[id*=post]"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            home,
            hasNext = document.selectFirst(
                "a.next, " +
                    "a.nextpostslink, " +
                    ".pagination a:contains(Next), " +
                    ".page-numbers a:contains(2), " +
                    "a[href*=/page/]:contains(2), " +
                    "ul.pagination li a:contains(»)"
            ) != null || home.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val endpoints = listOf(
            "$mainUrl/?s=$q&post_type[]=post&post_type[]=tv",
            "$mainUrl/?s=$q",
            "$mainUrl?s=$q"
        )
        val results = linkedMapOf<String, SearchResponse>()

        for (url in endpoints) {
            val document = runCatching { app.get(url, timeout = 50L).document }.getOrNull() ?: continue
            document.select(
                "article.item, " +
                    "article, " +
                    "div.gmr-box-content article, " +
                    "div.row article, " +
                    "div[id*=post]"
            ).mapNotNull { it.toSearchResult() }
                .forEach { results[it.url] = it }
            if (results.isNotEmpty()) break
        }

        return results.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url)
        val document = response.document

        val title = document.selectFirst(
            "h1.entry-title, " +
                "h1[itemprop=name], " +
                ".gmr-movie-title h1, " +
                "h1"
        )?.text()
            ?.cleanTitle()
            ?.ifBlank { null }
            ?: url.substringAfterLast("/").replace("-", " ").trim()

        val poster = fixUrlNull(
            document.selectFirst(
                "figure.pull-left > img, " +
                    ".content-thumbnail img, " +
                    ".gmr-movie-data img, " +
                    ".entry-content img, " +
                    "meta[property=og:image]"
            )?.getImageAttr()
        )?.fixImageQuality()

        val tags = document.select(
            "strong:contains(Genre) ~ a, " +
                "a[href*=/genre/], " +
                "a[href*=/category/]"
        ).eachText()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val year = document.selectFirst(
            "a[href*=/year/], " +
                "div.gmr-moviedata strong:contains(Year:) > a"
        )?.text()
            ?.trim()
            ?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""")
                .find(document.selectFirst(".gmr-moviedata, .entry-content, body")?.text().orEmpty())
                ?.value
                ?.toIntOrNull()

        val tvType = when {
            url.contains("/tv/", true) -> TvType.TvSeries
            document.selectFirst("div.vid-episodes a, div.gmr-listseries a, .vid-episodes a, .gmr-listseries a") != null -> TvType.TvSeries
            document.text().contains("TV Show", true) -> TvType.TvSeries
            document.text().contains("Eps:", true) -> TvType.TvSeries
            else -> TvType.Movie
        }

        val description = document.selectFirst(
            "div[itemprop=description] > p, " +
                "div[itemprop=description], " +
                ".entry-content p, " +
                ".gmr-movie-data p"
        )?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val trailer = document.selectFirst(
            "ul.gmr-player-nav li a.gmr-trailer-popup[href], " +
                "a.gmr-trailer-popup[href], " +
                "a[href*=youtube.com], " +
                "a[href*=youtu.be]"
        )?.attr("href")
            ?.takeIf { it.isNotBlank() }

        val rating = document.selectFirst(
            "div.gmr-meta-rating > span[itemprop=ratingValue], " +
                "span[itemprop=ratingValue], " +
                ".gmr-rating-item, " +
                ".rating"
        )?.text()
            ?.trim()
            ?.replace(",", ".")
            ?.let { Regex("""(\d+(\.\d+)?)""").find(it)?.groupValues?.getOrNull(1) }

        val actors = document.select("div.gmr-moviedata span[itemprop=actors] a, span[itemprop=actors] a, a[href*=/director/], a[href*=/actor/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val duration = document.selectFirst("span[property=duration], .runtime, .duration")?.text()
            ?.replace(Regex("\\D"), "")
            ?.toIntOrNull()
            ?: Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
                .find(document.text())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        val recommendations = (
            document.select("article.item").mapNotNull { it.toRecommendResult() } +
                document.select("div.idmuvi-rp ul li").mapNotNull { it.toRecommendResult() } +
                document.select(".gmr-related-post article").mapNotNull { it.toRecommendResult() }
            ).distinctBy { it.url }

        return if (tvType == TvType.TvSeries) {
            val episodes = parseEpisodes(document = document, fallbackUrl = url)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val baseUrl = getBaseUrl(data)
        val document = app.get(data).document
        val links = linkedSetOf<String>()

        val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")

        if (id.isNullOrBlank()) {
            document.select("ul.muvipro-player-tabs li a[href], .muvipro-player-tabs a[href], a[href*=player], a[href*=embed]").amap { element ->
                val playerPageUrl = fixUrl(element.attr("href"))
                val iframe = runCatching {
                    app.get(playerPageUrl, referer = data).document
                        .selectFirst("div.gmr-embed-responsive iframe, iframe")
                        ?.getIframeAttr()
                        ?.let { httpsify(it) }
                }.getOrNull()

                if (!iframe.isNullOrBlank()) links.add(iframe)
            }
        } else {
            document.select("div.tab-content-ajax[id], .tab-content-ajax[id]").amap { element ->
                val server = runCatching {
                    app.post(
                        "$baseUrl/wp-admin/admin-ajax.php",
                        referer = data,
                        data = mapOf(
                            "action" to "muvipro_player_content",
                            "tab" to element.attr("id"),
                            "post_id" to id,
                        ),
                    ).document
                        .selectFirst("iframe")
                        ?.getIframeAttr()
                        ?.let { httpsify(it) }
                }.getOrNull()

                if (!server.isNullOrBlank()) links.add(server)
            }
        }

        document.select(
            "ul.gmr-download-list li a[href], " +
                ".gmr-download-list a[href], " +
                "a[href*=hglink.to], " +
                "a[href*=ghbrisk.com], " +
                "a[href*=dhcplay.com], " +
                "a[href*=strp2p.com], " +
                "a[href*=rpmvid.com], " +
                "a[href*=dintezuvio.com], " +
                "a[href*=dingtezuni.com], " +
                "a[href*=movearnpre.com], " +
                "a[href*=mivalyo.com], " +
                "a[href*=bingezove.com], " +
                "a[href*=minochinos.com], " +
                "a[href*=ryderjet.com], " +
                "a[href*=hls-bekop.layarwibu.com]"
        ).forEach { linkElement ->
            val downloadUrl = linkElement.attr("href").trim()
            if (downloadUrl.isNotBlank()) links.add(fixUrl(downloadUrl))
        }

        links.forEach { link ->
            loadExtractor(link, "$baseUrl/", subtitleCallback, callback)
        }

        return links.isNotEmpty()
    }

    private fun parseEpisodes(document: org.jsoup.nodes.Document, fallbackUrl: String): List<Episode> {
        val episodeElements = document.select(
            "div.vid-episodes a[href], " +
                "div.gmr-listseries a[href], " +
                ".vid-episodes a[href], " +
                ".gmr-listseries a[href], " +
                "a[href*=/episode/]"
        )

        val episodes = episodeElements.mapIndexed { index, eps ->
            val href = fixUrl(eps.attr("href"))
            val episodeName = eps.text().trim().ifBlank { "Episode ${index + 1}" }
            val episodeNumber = extractEpisodeNumber(episodeName, href) ?: index + 1
            val seasonNumber = extractSeasonNumber(episodeName, href)

            newEpisode(href) {
                name = episodeName
                episode = episodeNumber
                season = seasonNumber
            }
        }.distinctBy { it.data }

        return episodes.ifEmpty {
            listOf(
                newEpisode(fallbackUrl) {
                    name = "Episode 1"
                    episode = 1
                }
            )
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst(
            "h2.entry-title > a[href], " +
                "h2 a[href], " +
                ".entry-title a[href], " +
                "a[rel=bookmark][href], " +
                "a[href][title], " +
                "a[href]"
        ) ?: return null

        val title = listOf(
            anchor.text().trim(),
            anchor.attr("title").trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Tonton", true) &&
                !it.equals("Tonton Film", true) &&
                !it.equals("Trailer", true)
        }?.cleanTitle() ?: return null

        val href = fixUrl(anchor.attr("href"))
        if (!href.startsWith(mainUrl)) return null

        val posterUrl = fixUrlNull(selectFirst("a > img, img")?.getImageAttr())?.fixImageQuality()
        val rating = selectFirst(".gmr-rating-item, .rating, span[itemprop=ratingValue]")?.text()
            ?.replace(",", ".")
            ?.let { Regex("""(\d+(\.\d+)?)""").find(it)?.groupValues?.getOrNull(1) }
            ?.toDoubleOrNull()
        val bodyText = text()
        val isTvSeries = href.contains("/tv/", true) ||
            selectFirst("div.gmr-numbeps > span, span.episode, .gmr-numbeps") != null ||
            bodyText.contains("TV Show", true) ||
            bodyText.contains("Eps:", true)

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                rating?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                rating?.let { this.score = Score.from10(it) }
            }
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val anchor = selectFirst("h2.entry-title > a[href], a > span.idmuvi-rp-title, a[href]") ?: return null
        val href = fixUrl(anchor.attr("href"))
        if (!href.startsWith(mainUrl)) return null

        val title = listOf(
            anchor.text().trim(),
            selectFirst("h2.entry-title > a")?.text()?.trim(),
            selectFirst("a > span.idmuvi-rp-title")?.text()?.trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim()
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?: return null

        val posterUrl = fixUrlNull(selectFirst("a > img, img")?.getImageAttr())?.fixImageQuality()
        val isTvSeries = href.contains("/tv/", true) || text().contains("Eps:", true) || text().contains("TV Show", true)

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    private fun Element.getImageAttr(): String {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            hasAttr("content") -> attr("content")
            else -> attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src")?.takeIf { it.isNotBlank() }
            ?: this?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: this?.attr("src")?.takeIf { it.isNotBlank() }
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val match = Regex("""-\d*x\d*(?=\.)""").find(this)?.value
        return if (match != null) replace(match, "") else this
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""(?i)^\s*permalink\s+ke:\s*"""), "")
            .replace(Regex("""(?i)\s+season\s+\d+.*$"""), "")
            .replace(Regex("""(?i)\s+episode\s+\d+.*$"""), "")
            .replace(Regex("""(?i)\s+subtitle\s+indonesia.*$"""), "")
            .replace(Regex("""(?i)\s+sub\s+indo.*$"""), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractEpisodeNumber(name: String, href: String): Int? {
        return (
            Regex("""(?:eps?|episode)\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(name)
                ?.groupValues
                ?.getOrNull(1)
                ?: Regex("""-episode-(\d+)""", RegexOption.IGNORE_CASE)
                    .find(href)
                    ?.groupValues
                    ?.getOrNull(1)
                ?: Regex("""\b(\d+)\b""").find(name)?.groupValues?.getOrNull(1)
            )?.toIntOrNull()
    }

    private fun extractSeasonNumber(name: String, href: String): Int? {
        return (
            Regex("""(?:^|\b)s(?:eason)?\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(name)
                ?.groupValues
                ?.getOrNull(1)
                ?: Regex("""-season-(\d+)""", RegexOption.IGNORE_CASE)
                    .find(href)
                    ?.groupValues
                    ?.getOrNull(1)
            )?.toIntOrNull()
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault(mainUrl)
    }
}
