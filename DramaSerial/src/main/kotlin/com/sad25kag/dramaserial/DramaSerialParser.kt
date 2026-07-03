package com.sad25kag.dramaserial

import com.lagradost.cloudstream3.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object DramaSerialParser {
    private val homeCardSelectors = listOf(
        "#gmr-main-load article.item",
        "#gmr-main-load article.item-infinite",
        ".gmr-module-posts .gmr-item-modulepost",
        "#gmr-main-load article",
        "article.item",
        "article.item-infinite",
        ".post-lst article",
        ".content-area article",
        ".site-main article",
        "article.post"
    ).joinToString(",")

    fun parseHomeItems(api: MainAPI, document: Document, baseUrl: String, defaultType: TvType = TvType.Movie): List<SearchResponse> {
        return document.select(homeCardSelectors)
            .mapNotNull { it.toSearchResponse(api, baseUrl, defaultType) }
            .distinctBy { it.url }
            .filter { it.name.length >= 2 }
    }

    private fun Element.toSearchResponse(api: MainAPI, baseUrl: String, defaultType: TvType = TvType.Movie): SearchResponse? {
        val titleElement = selectFirst("h2.entry-title a[href], .entry-title a[href], h3.entry-title a[href], h2 a[href], h3 a[href]")
        val imageAnchor = selectFirst(".content-thumbnail a[href], a[itemprop=url], .thumbnail a[href], .gmr-thumbnail a[href]")
        val anchor = titleElement ?: imageAnchor ?: selectFirst("a[href]") ?: return null
        val href = anchor.attr("href").absUrlDs(baseUrl) ?: return null
        if (!href.sameRootDomainDs(DramaSerialProvider.DEFAULT_MAIN_URL) && !href.sameHostDs(baseUrl)) return null
        if (href.isNoiseUrlDs()) return null
        // Block taxonomy/archive pages — both English and Indonesian variants
        if (href.contains("/Genre/", true) || href.contains("/genre/", true) ||
            href.contains("/year/", true) || href.contains("/tahun/", true) ||
            href.contains("/quality/", true) || href.contains("/kualitas/", true) ||
            href.contains("/country/", true) || href.contains("/negara/", true) ||
            href.contains("/author/", true) || href.contains("/sutradara/", true) ||
            href.contains("/cast/", true)) return null

        val title = listOf(
            titleElement?.text(),
            titleElement?.attr("title"),
            anchor.attr("title").removePrefix("Permalink to:"),
            selectFirst("img[alt]")?.attr("alt"),
            anchor.text()
        ).firstOrNull { !it.isNullOrBlank() }?.cleanTitleDs() ?: return null

        if (title.length < 2) return null
        if (title.equals("Watch Movie", true) || title.equals("Download", true) || title.equals("Watch", true) || title.equals("Trailer", true)) return null

        val poster = posterUrl(this, baseUrl)
        val type = inferType(title, href, defaultType)

        return when (type) {
            TvType.TvSeries, TvType.AsianDrama -> api.newTvSeriesSearchResponse(title, href, type) { posterUrl = poster }
            else -> api.newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }

    fun posterUrl(element: Element, baseUrl: String): String? {
        val candidates = mutableListOf<String>()
        element.select(".content-thumbnail img, img.wp-post-image, img, source").forEach { img ->
            listOf(
                img.attr("data-src"),
                img.attr("data-lazy-src"),
                img.attr("data-original"),
                img.attr("data-image"),
                img.attr("data-poster"),
                img.attr("poster"),
                img.attr("src")
            ).filter { it.isNotBlank() && !it.startsWith("data:") }.forEach(candidates::add)

            listOf("srcset", "data-srcset").forEach { attr ->
                img.attr(attr)
                    .split(",")
                    .map { it.trim().substringBefore(" ").trim() }
                    .filter { it.isNotBlank() && !it.startsWith("data:") }
                    .forEach(candidates::add)
            }
        }

        return candidates.distinct()
            .filterNot { value ->
                val lower = value.lowercase()
                lower.contains("logo") || lower.contains("favicon") || lower.contains("placeholder") || lower.contains("luxury") || lower.endsWith(".gif")
            }
            .sortedByDescending { it.posterCandidateScoreDs() }
            .firstOrNull()
            ?.replace(Regex("""-\d+x\d+(?=\.(?:jpg|jpeg|png|webp))""", RegexOption.IGNORE_CASE), "")
            ?.absUrlDs(baseUrl)
    }

    fun parseTitle(document: Document): String {
        return listOf(
            document.selectFirst("h1.entry-title")?.text(),
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("meta[name=title]")?.attr("content"),
            document.title()
        ).firstOrNull { !it.isNullOrBlank() }?.cleanTitleDs().orEmpty()
    }

    fun parsePoster(document: Document, pageUrl: String): String? {
        return listOf(
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst("meta[name=twitter:image]")?.attr("content"),
            document.selectFirst("article.single-thumb img.wp-post-image")?.attr("src"),
            document.selectFirst(".content-thumbnail img.wp-post-image")?.attr("src"),
            document.selectFirst(".content-thumbnail img")?.let {
                listOf(it.attr("data-src"), it.attr("data-lazy-src"), it.attr("src"))
                    .firstOrNull { value -> value.isNotBlank() && !value.startsWith("data:") }
            }
        ).firstOrNull { !it.isNullOrBlank() }?.absUrlDs(pageUrl) ?: posterUrl(document.body(), pageUrl)
    }

    fun parsePlot(document: Document): String? {
        val firstParagraph = document.selectFirst(".entry-content.entry-content-single > p")?.text()
            ?: document.selectFirst(".entry-content > p")?.text()
        return listOf(
            firstParagraph,
            document.selectFirst("meta[property=og:description]")?.attr("content"),
            document.selectFirst("meta[name=description]")?.attr("content")
        ).firstOrNull { !it.isNullOrBlank() }?.cleanDs()?.takeIf { it.length > 10 }
    }

    fun parseTags(document: Document): List<String> {
        val genreBlock = document.select(".gmr-moviedata, .moviedata, .movie-data")
            .firstOrNull { it.selectFirst("strong")?.text()?.contains("Genre", ignoreCase = true) == true }
        return (genreBlock?.select("a") ?: emptyList<Element>())
            .map { it.text().cleanDs() }
            .filterNot { it.startsWith("Film Seri", true) || it.equals("Anime", true) }
            .filter { it.isNotBlank() && it.length <= 40 }
            .distinct()
    }

    fun parseYear(document: Document, title: String): Int? {
        val yearBlock = document.select(".gmr-moviedata, .moviedata, .movie-data")
            .firstOrNull { it.selectFirst("strong")?.text()?.contains("Year", ignoreCase = true) == true }
            ?.text()
        return Regex("""\b(19\d{2}|20\d{2})\b""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: yearBlock?.let { Regex("""\b(19\d{2}|20\d{2})\b""").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
    }

    fun parseEpisodes(api: MainAPI, document: Document, pageUrl: String): List<Episode> {
        // Primary selectors: common drama/series plugin CSS classes
        val selectors = listOf(
            ".gmr-listseries a[href]",
            ".gmr-eps-list a[href]",
            "ul.gmr-episodes li a[href]",
            ".episodios a[href]",
            ".eplister a[href]",
            ".list-episode a[href]",
            ".entry-content a[href*='episode']",
            "a[href*='/episode-']",
            "a[href*='?episode=']"
        ).joinToString(",")

        val foundEps = document.select(selectors).mapNotNull { element ->
            val href = element.attr("href").absUrlDs(pageUrl) ?: return@mapNotNull null
            if (href == pageUrl || href.isNoiseUrlDs()) return@mapNotNull null
            val text = element.text().cleanDs()
            val number = Regex("""(?i)(?:episode|eps|ep|e)[-_\s]*(\d+)""").find("$text $href")?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""^\D*(\d+)\D*$""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val season = Regex("""(?i)(?:season|s)[-_\s]*(\d+)""").find("$text $href")?.groupValues?.getOrNull(1)?.toIntOrNull()
            api.newEpisode(href) {
                name = text.takeIf { it.isNotBlank() && it.length > 1 } ?: number?.let { "Episode $it" } ?: "Episode"
                episode = number
                this.season = season
            }
        }.distinctBy { it.data }

        if (foundEps.isNotEmpty()) return foundEps

        // ── DramaSerial numeric episode pagination fallback ──────────────────────────────
        // DramaSerial structures series as:
        //   Series root : /film-seri/nonton-X/          ← episode 1 (no link, is the current page)
        //   Episode 2   : /film-seri/nonton-X/2/
        //   Episode N   : /film-seri/nonton-X/N/
        //
        // Only apply this fallback when we are on a series root URL (last path segment is
        // not a plain integer — that would mean we are already on an episode sub-page).
        val lastSegment = pageUrl.trimEnd('/').substringAfterLast('/')
        val isEpisodeSubPage = lastSegment.isNotBlank() && lastSegment.all { it.isDigit() }
        if (!isEpisodeSubPage) {
            val baseUrl = pageUrl.trimEnd('/')
            val numericEps = document.select("a[href]")
                .mapNotNull { element ->
                    val href = element.attr("href").absUrlDs(pageUrl)?.trimEnd('/') ?: return@mapNotNull null
                    if (!href.startsWith("$baseUrl/")) return@mapNotNull null
                    val suffix = href.removePrefix("$baseUrl/")
                    val epNum = suffix.toIntOrNull() ?: return@mapNotNull null  // must be a plain integer
                    Pair(epNum, "$href/")
                }
                .distinctBy { it.second }
                .sortedBy { it.first }

            if (numericEps.isNotEmpty()) {
                return numericEps.map { (epNum, href) ->
                    api.newEpisode(href) {
                        name = "Episode $epNum"
                        episode = epNum
                    }
                }.distinctBy { it.data }
            }
        }

        return emptyList()
    }

    fun hasNextPage(document: Document, page: Int): Boolean {
        return document.select("a.next.page-numbers, .pagination a.next, .nav-links a.next, .gmr-pagination a.next").isNotEmpty() ||
            document.select("a.page-numbers").any { it.text().trim().toIntOrNull()?.let { number -> number > page } == true }
    }

    fun inferType(title: String, url: String, defaultType: TvType = TvType.Movie): TvType {
        val text = "$title $url".lowercase()
        return when {
            text.contains("/tv/") || text.contains("/film-seri/") || text.contains("series") || text.contains("season") || text.contains("episode") || text.contains("eps ") -> TvType.AsianDrama
            text.contains("drama china") || text.contains("drama korea") || text.contains("drakor") || text.contains("serial") -> TvType.AsianDrama
            else -> defaultType
        }
    }

    fun parseDocumentFromHtml(html: String, baseUrl: String): Document = Jsoup.parse(html.cleanDs(), baseUrl)
}
