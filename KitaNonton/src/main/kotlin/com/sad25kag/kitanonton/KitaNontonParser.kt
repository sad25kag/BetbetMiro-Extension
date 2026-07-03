package com.kitanonton

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.kitanonton.KitaNontonUtils.absoluteUrl
import com.kitanonton.KitaNontonUtils.cleanText
import com.kitanonton.KitaNontonUtils.isValidContentUrl
import com.kitanonton.KitaNontonUtils.typeFrom
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object KitaNontonParser {
    private const val IMAGE_SELECTOR = "img[src], img[data-src], img[data-lazy-src], img[data-original], img[data-srcset], img[srcset]"
    private val cardSelector = listOf(
        "article.item.movies",
        "article.item.tvshows",
        "article.item",
        ".items article",
        ".result-item",
        ".movie-item",
        ".ml-item",
        ".post:has(img)",
        ".item:has(img)",
        "a[href]:has(img)"
    ).joinToString(",")

    fun parseListing(api: MainAPI, document: Document): List<SearchResponse> {
        return document.select(cardSelector)
            .mapNotNull { parseCard(api, it) }
            .distinctBy { it.url }
            .take(60)
    }

    private fun parseCard(api: MainAPI, element: Element): SearchResponse? {
        val link = when {
            element.tagName().equals("a", true) && element.hasAttr("href") -> element
            else -> element.selectFirst("a[href]:has(img)")
                ?: element.selectFirst("h2 a[href], h3 a[href], .title a[href], .data h3 a[href], a[title][href]")
                ?: return null
        }
        val href = absoluteUrl(api.mainUrl, link.attr("href")) ?: return null
        if (!isValidContentUrl(href)) return null
        val image = link.selectFirst(IMAGE_SELECTOR) ?: element.selectFirst(IMAGE_SELECTOR)
        val title = cleanTitle(
            link.attr("title")
                .ifBlank { link.attr("aria-label") }
                .ifBlank { link.text() }
                .ifBlank { element.selectFirst(".data h3, h3, h2, .title")?.text().orEmpty() }
                .ifBlank { image?.attr("alt").orEmpty() }
        ).ifBlank { return null }
        return api.newMovieSearchResponse(title, href, typeFrom(href, title)) {
            posterUrl = poster(api.mainUrl, image, element)
        }
    }

    suspend fun parseLoadResponse(api: MainAPI, url: String, document: Document): LoadResponse? {
        val title = cleanDetailTitle(
            document.selectFirst("h1.entry-title, h1.post-title, .single-title h1, .entry-header h1, h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()
        ).ifBlank { return null }
        val poster = poster(api.mainUrl, document.selectFirst(".poster img, .thumb img, .wp-post-image, img[alt], img[title]"), document)
            ?: absoluteUrl(api.mainUrl, document.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = cleanText(
            document.selectFirst(".desc, .description, .entry-content p, .sinopsis, .synopsis, .wp-content p")?.text()
                ?: document.selectFirst("meta[name=description]")?.attr("content")
                ?: document.selectFirst("meta[property=og:description]")?.attr("content")
        )
        val tags = document.select("a[href*='/genre/'], a[href*='/country/'], a[href*='/year/']")
            .map { cleanTitle(it.text()) }
            .filter { it.length in 2..35 }
            .distinct()
            .take(16)
        val recommendations = parseRecommendations(api, document, url)
        val episodes = parseEpisodes(api, document, url)
        val type = if (episodes.size > 1 || typeFrom(url, title) == TvType.TvSeries) TvType.TvSeries else TvType.Movie
        return if (type == TvType.TvSeries) {
            api.newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            api.newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    private fun parseEpisodes(api: MainAPI, document: Document, fallbackUrl: String): List<Episode> {
        val seen = linkedSetOf<String>()
        val selectors = listOf(
            "#seasons a[href]",
            ".se-c a[href]",
            ".episodios a[href]",
            ".episodes a[href]",
            ".episodelist a[href]",
            ".episode a[href]",
            ".eps a[href]",
            "a[href*='/episode/']",
            "a[href*='/episodes/']",
            "a[href*='season']"
        ).joinToString(",")
        val episodes = document.select(selectors).mapNotNull { anchor ->
            val href = absoluteUrl(KitaNontonUtils.MAIN_URL, anchor.attr("href")) ?: return@mapNotNull null
            if (!isValidContentUrl(href)) return@mapNotNull null
            if (!seen.add(href.substringBefore("#"))) return@mapNotNull null
            val raw = cleanTitle(anchor.text()).ifBlank { cleanTitle(anchor.attr("title")) }.ifBlank { titleFromUrl(href) ?: "Episode" }
            api.newEpisode(href) {
                name = raw
                episode = episodeNumber(raw, href)
                season = seasonNumber(raw, href)
            }
        }
        return episodes.ifEmpty {
            listOf(api.newEpisode(fallbackUrl) { name = if (fallbackUrl.contains("tv", true)) "Episode" else "Movie" })
        }
    }

    private fun parseRecommendations(api: MainAPI, document: Document, currentUrl: String): List<SearchResponse> {
        val related = document.selectFirst(".related, .recommended, .recommend, .similar, #related")
            ?.select(cardSelector)
            ?.mapNotNull { parseCard(api, it) }
            .orEmpty()
        return related.filterNot { it.url == currentUrl }.distinctBy { it.url }.take(12)
    }

    private fun poster(baseUrl: String, image: Element?, container: Element? = null): String? {
        val candidates = mutableListOf<String?>()
        if (image != null) {
            listOf("data-src", "data-lazy-src", "data-original", "src").forEach { candidates += image.attr(it).takeIf { value -> value.isNotBlank() } }
            listOf("data-srcset", "srcset").forEach { attr ->
                image.attr(attr).split(',').map { it.trim().substringBefore(' ') }.filter { it.isNotBlank() }.forEach { candidates += it }
            }
        }
        if (container != null) {
            candidates += container.selectFirst("noscript img[src]")?.attr("src")
            candidates += container.selectFirst("meta[property=og:image]")?.attr("content")
        }
        return candidates.asSequence().mapNotNull { absoluteUrl(baseUrl, it)?.replace(Regex("-\\d+x\\d+(?=\\.(?:jpg|jpeg|png|webp)(?:\\?|$))", RegexOption.IGNORE_CASE), "") }
            .firstOrNull { it.startsWith("http") && !it.contains("logo", true) && !it.endsWith(".svg", true) }
    }

    private fun cleanTitle(value: String?): String = cleanText(value)
        .replace(Regex("(?i)\\b(nonton|streaming|download|subtitle indonesia|sub indo|bluray|webrip|web-dl|hdrip|brrip|dvdrip)\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '-', '|', ':')

    private fun cleanDetailTitle(value: String?): String = cleanTitle(value)
        .substringBefore(" - KitaNonton", "")
        .substringBefore(" | KitaNonton", "")
        .ifBlank { cleanTitle(value) }

    private fun titleFromUrl(url: String): String? {
        val slug = url.trimEnd('/').substringAfterLast('/').substringBefore('?')
        if (slug.isBlank()) return null
        return slug.replace('-', ' ').replace('_', ' ').split(' ')
            .joinToString(" ") { part -> part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
    }

    private fun episodeNumber(text: String, url: String): Int? {
        val raw = "$text $url"
        return Regex("(?i)(?:episode|eps|ep)[^0-9]{0,4}(\\d{1,4})").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?i)[?&]episode=(\\d{1,4})").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun seasonNumber(text: String, url: String): Int? {
        val raw = "$text $url"
        return Regex("(?i)(?:season|seas|s)[^0-9]{0,4}(\\d{1,3})").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}
