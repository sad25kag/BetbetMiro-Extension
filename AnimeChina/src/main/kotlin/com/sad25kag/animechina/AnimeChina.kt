package com.sad25kag.animechina

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

class AnimeChina : MainAPI() {
    override var mainUrl = "https://animechina.my.id"
    override var name = "AnimeChina"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.7,en;q=0.5",
        "Referer" to "$mainUrl/",
    )

    private val knownPlayerHosts = listOf(
        "ok.ru", "dailymotion.com", "anichin.stream", "drive.google.com",
        "rumble.com", "filemoon.", "streamtape.", "dood.", "vidhide.",
        "vidguard.", "voe.", "mixdrop.", "mp4upload.", "sendvid.",
        "blogger.com", "googlevideo.com", "mega.nz", "sbembed.",
        "short.ink", "racaty.", "rubystream.", "streamruby.", "filelions.",
        "abyssplayer."
    )

    override val mainPage = mainPageOf(
        "$mainUrl/latest-update/?order=DESC&type=tv" to "Donghua Series Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url, headers = browserHeaders).document
        val results = parseAnimeChinaCards(document)
        val hasNext = document.selectFirst(
            "a.next[href], .pagination a[href*='/page/${page + 1}/'], a[href*='/page/${page + 1}/'], a[href*='page/${page + 1}']"
        ) != null
        return newHomePageResponse(request.name, results, hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")

        val url = if (page <= 1) {
            "$mainUrl/?s=$encoded&post_type=post"
        } else {
            "$mainUrl/page/$page/?s=$encoded&post_type=post"
        }

        val document = try {
            app.get(url, referer = mainUrl).document
        } catch (_: Exception) {
            return null
        }

        val results = parseAnimeChinaCards(document)

        val hasNext = document.select("a[href]").any {
            val href = it.attr("href")
            href.contains("/page/${page + 1}/")
        }

        return newSearchResponseList(
            results,
            hasNext = hasNext
        )
    }


    override suspend fun load(url: String): LoadResponse? {
        val canonicalUrl = canonicalSeriesUrl(url)
        val document = app.get(canonicalUrl, headers = browserHeaders).document

        val title = cleanTitle(
            document.selectFirst("h1.entry-title, h1, h2[itemprop=name], .entry-title, .title")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()
        ) ?: return null

        val poster = document.selectFirst(
            ".info__poster img.wp-post-image, .info__poster img, .thumb img, .poster img, .bigcover img, .mvic-desc img"
        )?.imageUrl(canonicalUrl)
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrl(canonicalUrl)
            ?: document.select("article img").firstOrNull { el ->
                val src = el.attr("src").ifBlank { el.attr("data-src") }
                src.isNotBlank() && !src.contains("logo") && !src.contains("cropped")
            }?.imageUrl(canonicalUrl)

        val plot = extractPlot(document)
            ?: document.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")
                ?.cleanText()?.takeIf { it.isGoodPlot(title) }

        val tags = document.select("a[href*='/genres/']").map { it.text().cleanText() }
            .filter { it.isValidGenreTag() }.distinct()

        val episodes = parseEpisodeList(document, canonicalUrl)

        val recommendations = document.select(".recommended a[href*='/watch/'], .related a[href*='/watch/']").mapNotNull { a ->
            val href = a.attr("href").toAbsoluteUrl(canonicalUrl) ?: return@mapNotNull null
            val imgTitle = a.selectFirst("img")?.attr("alt")?.cleanText()?.takeIf { it.length > 2 }
                ?: a.selectFirst("h2, h3, .title")?.text()?.cleanText()?.takeIf { it.length > 2 }
                ?: return@mapNotNull null
            val poster2 = a.selectFirst("img")?.imageUrl(canonicalUrl)
            newMovieSearchResponse(imgTitle, canonicalSeriesUrl(href), TvType.Anime) {
                this.posterUrl = poster2
            }
        }.take(12)

        return newTvSeriesLoadResponse(title, canonicalUrl, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
            this.showStatus = detectStatus(document)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data, headers = browserHeaders, referer = "$mainUrl/").document
        val visited = linkedSetOf<String>()
        val emitted = linkedSetOf<String>()

        fun countingCallback(link: ExtractorLink) {
            if (emitted.add(link.url.substringBefore("#"))) {
                callback.invoke(link)
            }
        }

        suspend fun tryExtractor(playerUrl: String, referer: String = data) {
            val key = playerUrl.substringBefore("#")
            if (!visited.add(key)) return
            runCatching {
                loadExtractor(playerUrl, referer, subtitleCallback, ::countingCallback)
            }
        }

        val contentLinks = document.select(
            ".the__content a[href], .entry-content a[href], .post-content a[href], article .content a[href]"
        ).mapNotNull { it.attr("href").toAbsoluteUrl(data) }
            .filter { url -> knownPlayerHosts.any { host -> url.contains(host, ignoreCase = true) } }

        for (playerUrl in contentLinks) {
            tryExtractor(playerUrl)
        }

        val mirrorCandidates = linkedSetOf<String>()
        document.select("select option[value], .mirror option[value], .mobius option[value]").forEach { opt ->
            val value = opt.attr("value").trim()
            if (value.isNotBlank()) {
                val decoded = runCatching { base64Decode(value) }.getOrNull().orEmpty()
                val iframeUrl = if (decoded.contains("<iframe", ignoreCase = true)) {
                    org.jsoup.Jsoup.parse(decoded).selectFirst("iframe[src]")?.attr("src")
                } else {
                    decoded.ifBlank { null }
                }
                iframeUrl?.toAbsoluteUrl(data)?.let { mirrorCandidates.add(it) }
                    ?: value.toAbsoluteUrl(data)?.let { mirrorCandidates.add(it) }
            }
        }

        document.select("iframe[src], embed[src]").forEach { element ->
            element.attr("src").toAbsoluteUrl(data)?.let { mirrorCandidates.add(it) }
        }

        for (playerUrl in mirrorCandidates) {
            tryExtractor(playerUrl)
        }

        if (emitted.isEmpty()) {
            document.select("script").forEach { script ->
                val text = script.html()
                val unpacked = runCatching { getAndUnpack(text) }.getOrNull().orEmpty()
                val matches = Regex("""https?:\\/\\/[^'"<>\\\s]+""", RegexOption.IGNORE_CASE)
                    .findAll(text + "\n" + unpacked)
                for (match in matches) {
                    val playerUrl = match.value.replace("\\/", "/").toAbsoluteUrl(data) ?: continue
                    if (knownPlayerHosts.any { host -> playerUrl.contains(host, ignoreCase = true) }) {
                        runCatching { tryExtractor(playerUrl) }
                    }
                }
            }
        }

        return emitted.isNotEmpty()
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val cleanPath = path.trim().trimStart('/')
        val base = if (cleanPath.startsWith("http", true)) cleanPath.trimEnd('/')
        else if (cleanPath.isBlank()) mainUrl
        else "$mainUrl/$cleanPath".trimEnd('/')
        if (page <= 1) return "$base/"
        return "$base/page/$page/"
    }

    private fun parseAnimeChinaCards(document: Document): List<SearchResponse> {
        return document.select("a[href*='/watch/']").mapNotNull { it.toAnimeChinaCard() }
            .filterNot { it.name.equals("Watch", true) || it.name.startsWith("Episode ", true) }
            .distinctBy { it.url.normalizedKey() }
    }

    private fun Element.toAnimeChinaCard(): SearchResponse? {
        val href = attr("href").toAbsoluteUrl() ?: return null
        if (!href.contains("/watch/", true) || !href.startsWith(mainUrl)) return null
        val rawTitle = attr("title").cleanText().takeIf { it.length > 2 }
            ?: selectFirst("img")?.attr("alt")?.cleanText()?.takeIf { it.length > 2 }
            ?: text().cleanText().takeIf { it.length > 2 }
            ?: return null
        val title = cleanCardTitle(rawTitle).takeIf { it.length > 2 } ?: return null
        val poster = selectFirst("img")?.imageUrl(href)
        val responseUrl = canonicalSeriesUrl(href)
        return newMovieSearchResponse(title, responseUrl, TvType.Anime) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    private fun parseEpisodeList(document: Document, baseUrl: String): List<Episode> {
        val anchors = document.select("div.gsd a[href*='episode='], .gsd a[href*='episode=']").ifEmpty {
            document.select("a[href*='episode=']").filterNot { anchor ->
                val href = anchor.attr("href")
                href.contains("/genres/") || href.contains("/category/")
            }
        }

        val episodes = anchors.mapNotNull { anchor ->
            val href = anchor.attr("href").toAbsoluteUrl(baseUrl) ?: return@mapNotNull null
            if (!href.contains("episode=", ignoreCase = true)) return@mapNotNull null
            val episodeNumber = href.substringAfter("episode=").substringBefore("&").toIntOrNull()
                ?: anchor.text().toEpisodeNumber()
            newEpisode(href) {
                this.name = anchor.text().cleanText().takeIf { it.isNotBlank() } ?: episodeNumber?.let { "Episode $it" }
                this.episode = episodeNumber
            }
        }.distinctBy { it.data.normalizedKey() }
            .sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name ?: "" })

        if (episodes.isNotEmpty()) return episodes

        val currentEpisode = baseUrl.toEpisodeNumber()
        return if (currentEpisode != null) {
            listOf(newEpisode(baseUrl) {
                this.name = "Episode $currentEpisode"
                this.episode = currentEpisode
            })
        } else {
            listOf(newEpisode(baseUrl) { this.name = "Play" })
        }
    }

    private fun extractPlot(document: Document): String? {
        return document.select(
            ".the__content p, .info__ori .the__content p, .entry-content p, .synopsis p, .desc p, .storyline p"
        ).map { it.text().cleanText() }
            .filter { it.isGoodPlot() }
            .distinct()
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }
    }

    private fun String.isGoodPlot(title: String? = null): Boolean {
        val value = cleanText()
        if (value.isBlank() || value.length < 20) return false
        if (title != null && value.equals(title, ignoreCase = true)) return false
        if (value.contains("Share on", true)) return false
        if (value.contains("Article Rating", true)) return false
        if (value.contains("Login", true) && value.contains("Register", true)) return false
        if (value.contains("Nonton Donghua Sub Indo", true) && value.length < 90) return false
        return true
    }

    private fun String.isValidGenreTag(): Boolean {
        val value = cleanText()
        if (value.isBlank() || value.equals("All Genres", true)) return false
        if (Regex("""^\d{4}$""").matches(value)) return false
        if (value.length < 3) return false
        if (value.contains("Episode", true)) return false
        if (value.contains("Subtitle", true)) return false
        if (value.contains("Nonton", true)) return false
        return true
    }

    private fun cleanCardTitle(raw: String): String {
        return raw.cleanText()
            .replace(Regex("(?i)^HD\\s*"), "")
            .replace(Regex("(?i)^Episode\\s*\\d+\\s*"), "")
            .replace(Regex("(?i)^Eps?\\s*\\d+\\s*"), "")
            .replace(Regex("(?i)\\s*Subtitle\\s*Indonesia.*$"), "")
            .replace(Regex("(?i)\\s*Sub\\s*Indo.*$"), "")
            .trim()
    }

    private fun cleanTitle(raw: String?): String? {
        return raw?.cleanText()
            ?.replace(Regex("(?i)^Nonton\\s+Donghua\\s+"), "")
            ?.replace(Regex("(?i)\\s+Subtitle\\s+Indonesia.*$"), "")
            ?.replace(Regex("(?i)\\s+-\\s+AnimeChina.*$"), "")
            ?.replace(Regex("(?i)^HD\\s*"), "")
            ?.trim()
            ?.takeIf { it.length > 1 }
    }

    private fun canonicalSeriesUrl(url: String): String {
        return url.substringBefore("?").trimEnd('/') + "/"
    }

    private fun detectStatus(document: Document): ShowStatus? {
        val text = document.text().lowercase(Locale.ROOT)
        return when {
            "completed" in text || "complete" in text || "tamat" in text -> ShowStatus.Completed
            "ongoing" in text || "sedang tayang" in text -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun Element.imageUrl(referer: String = mainUrl): String? {
        val image = if (tagName().equals("img", true)) this else selectFirst("img")
        return listOf("data-src", "data-original", "data-lazy-src", "src")
            .firstNotNullOfOrNull { key ->
                image?.attr(key)?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            }?.toAbsoluteUrl(referer)
    }

    private fun String.toAbsoluteUrl(base: String = mainUrl): String? {
        val value = trim().trim('\'', '"').replace("\\/", "/")
        if (value.isBlank() || value.startsWith("javascript:", true) || value == "#") return null
        if (value.startsWith("//")) return "https:$value"
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        return runCatching { URI(base).resolve(value).toString() }.getOrNull()
    }

    private fun String.cleanText(): String {
        return replace("\u00a0", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String.toEpisodeNumber(): Int? {
        return Regex("(?i)(?:episode|eps?|ep)[^0-9]{0,4}(\\d+)").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: substringAfter("episode=", "").substringBefore("&").toIntOrNull()
    }

    private fun String.normalizedKey(): String = substringBefore("#").trimEnd('/').lowercase(Locale.ROOT)
}
