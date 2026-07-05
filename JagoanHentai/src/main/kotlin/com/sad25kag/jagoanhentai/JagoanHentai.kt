package com.sad25kag.jagoanhentai

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class JagoanHentai : MainAPI() {
    override var mainUrl = "https://jagoanhentai.fun"
    override var name = "JagoanHentai"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.7,en;q=0.5",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "/" to "Episode Terbaru",
        "/anime/?status=&type=&order=update" to "Series Update",
        "/anime/?status=ongoing&type=&order=update" to "Ongoing",
        "/anime/?status=completed&type=&order=update" to "Completed",
        "/anime/?genre[]=big-oppai&status=&type=&order=update" to "Big Oppai",
        "/anime/?genre[]=blowjob&status=&type=&order=update" to "Blowjob",
        "/anime/?genre[]=paihame&status=&type=&order=update" to "Paihame",
        "/anime/?genre[]=paizuri&status=&type=&order=update" to "Paizuri",
        "/anime/?genre[]=netorare&status=&type=&order=update" to "Netorare",
        "/anime/?genre[]=romance&status=&type=&order=update" to "Romance",
        "/anime/?genre[]=schoolgirl&status=&type=&order=update" to "Schoolgirl",
        "/anime/?genre[]=virgin&status=&type=&order=update" to "Virgin",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = safeDocument(buildPageUrl(request.data, page), mainUrl)
            ?: return newHomePageResponse(request.name, emptyList(), false)
        val results = when {
            request.data == "/" -> parseHomeLatestEpisodes(document)
            request.data.startsWith("/anime/", ignoreCase = true) -> parseAnimeListing(document)
            else -> emptyList()
        }
            .filterNot { it.isBlockedResult() }
            .distinctBy { it.url.normalizeKey() }

        val hasNext = document.selectFirst(".hpage a[href]:contains(Selanjutnya), .hpage a[href]:contains(Next), a.next[href], a[rel=next]") != null
        return newHomePageResponse(request.name, results, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val routes = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/anime/?s=$encoded",
        )

        return routes.flatMap { url ->
            val document = safeDocument(url, mainUrl) ?: return@flatMap emptyList()
            parseAnimeListing(document) + parseHomeLatestEpisodes(document)
        }
            .filterNot { it.isBlockedResult() }
            .distinctBy { it.url.normalizeKey() }
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.isBlockedTerm()) return null
        val document = safeDocument(url, mainUrl) ?: return null
        if (document.isBlockedPage()) return null

        val title = cleanTitle(
            document.selectFirst("h1.entry-title, .bigcontent h1, h1[itemprop=name], h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title(),
        ) ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrl()
            ?: document.selectFirst(".thumbook img, .thumb img, .tb img, .bigcontent img, .single-info img, article img")?.imageUrl()

        val plot = document.selectFirst(".synp .entry-content p, .synp .entry-content, .entry-content p, .entry-content, meta[name=description]")?.let {
            if (it.tagName().equals("meta", true)) it.attr("content") else it.text()
        }?.cleanText()

        val tags = document.select(".genxed a[href*='/genres/'], a[rel=tag]")
            .map { it.text().cleanText() }
            .filter { it.isNotBlank() && !it.isBlockedTerm() }
            .distinct()

        val recommendations = parseRecommendations(document)
            .filterNot { it.url.normalizeKey() == url.normalizeKey() || it.isBlockedResult() }
            .take(16)

        val episodes = parseEpisodes(document)
            .filterNot { it.data.isBlockedTerm() || (it.name ?: "").isBlockedTerm() }
            .distinctBy { it.data.normalizeKey() }

        return if (episodes.isNotEmpty() && !looksEpisodePage(url, title)) {
            newTvSeriesLoadResponse(
                title,
                url,
                TvType.NSFW,
                episodes.sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name }),
            ) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                this.showStatus = detectStatus(document)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.isBlockedTerm()) return false
        val document = safeDocument(data, mainUrl) ?: return false
        if (document.isBlockedPage()) return false

        val emitted = linkedSetOf<String>()
        val candidates = linkedSetOf<String>()
        candidates.addAll(collectStaticPlayers(document, data))
        candidates.addAll(collectMirrorPlayers(document, data))
        candidates.addAll(collectScriptPlayers(document.html(), data))

        suspend fun emitDirect(rawUrl: String, referer: String): Boolean {
            val fixed = rawUrl.toAbsoluteUrl(referer) ?: return false
            if (!fixed.isDirectMedia()) return false
            if (!emitted.add(fixed.substringBefore("#"))) return true
            val sourceName = "$name - ${fixed.hostLabel()}"
            val headers = browserHeaders + mapOf(
                "Accept" to "*/*",
                "Referer" to referer,
            )

            if (fixed.contains(".m3u8", ignoreCase = true)) {
                M3u8Helper.generateM3u8(sourceName, fixed, referer = referer, headers = headers).forEach(callback)
            } else {
                callback.invoke(
                    newExtractorLink(sourceName, sourceName, fixed, ExtractorLinkType.VIDEO) {
                        this.referer = referer
                        this.quality = getQualityFromName(fixed)
                        this.headers = headers
                    },
                )
            }
            return true
        }

        for (candidate in candidates.take(40)) {
            val playerUrl = candidate.toAbsoluteUrl(data) ?: continue
            if (!playerUrl.isSupportedPlayerUrl() && !playerUrl.isDirectMedia()) continue

            if (emitDirect(playerUrl, data)) continue

            val before = emitted.size
            val countedCallback: (ExtractorLink) -> Unit = { link ->
                emitted.add(link.url.substringBefore("#"))
                callback.invoke(link)
            }

            runCatching { loadExtractor(playerUrl, data, subtitleCallback, countedCallback) }
            if (emitted.size > before) continue

            val playerHtml = runCatching {
                app.get(
                    playerUrl,
                    headers = browserHeaders + mapOf("Referer" to data),
                    referer = data,
                ).text
            }.getOrDefault("")

            collectScriptPlayers(playerHtml, playerUrl).take(12).forEach { nestedUrl ->
                if (!emitDirect(nestedUrl, playerUrl)) {
                    runCatching { loadExtractor(nestedUrl, playerUrl, subtitleCallback, countedCallback) }
                }
            }
        }

        return emitted.isNotEmpty()
    }

    private suspend fun safeDocument(url: String, referer: String): Document? {
        val referers = listOf(referer, "$mainUrl/", mainUrl).distinct()
        for (attempt in 0 until 3) {
            val attemptReferer = referers.getOrElse(attempt) { referer }
            val document = runCatching {
                app.get(
                    url,
                    headers = browserHeaders + mapOf("Referer" to attemptReferer),
                    referer = attemptReferer,
                ).document
            }.getOrNull()

            if (document != null && !document.isBlockedPage()) return document
        }
        return null
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val base = when {
            path == "/" || path.isBlank() -> mainUrl
            path.startsWith("http", ignoreCase = true) -> path
            else -> mainUrl + path
        }
        if (page <= 1) return base
        if (!base.contains("?")) return base.trimEnd('/') + "/page/$page/"

        val cleanBase = base.substringBefore("?").trimEnd('/')
        val query = base.substringAfter("?", "")
        return "$cleanBase/page/$page/?$query"
    }

    private fun parseHomeLatestEpisodes(document: Document): List<SearchResponse> {
        val section = document.select(".bixbox").firstOrNull { box ->
            box.selectFirst(".releases")?.text()?.contains("Rilisan Terbaru", ignoreCase = true) == true
        } ?: document

        return section.select(".listupd article, .listupd .bs")
            .mapNotNull { it.toEpisodeCard() }
            .distinctBy { it.url.normalizeKey() }
    }

    private fun parseAnimeListing(document: Document): List<SearchResponse> {
        val section = document.select(".bixbox").firstOrNull { box ->
            box.selectFirst(".releases")?.text()?.contains("Anime Lists", ignoreCase = true) == true
        } ?: document

        return section.select(".listupd article, .listupd .bs")
            .mapNotNull { it.toSeriesCard() }
            .distinctBy { it.url.normalizeKey() }
    }

    private fun parseRecommendations(document: Document): List<SearchResponse> {
        val section = document.select(".bixbox").firstOrNull { box ->
            box.selectFirst(".releases")?.text()?.contains("Rekomendasi", ignoreCase = true) == true
        }

        return (section ?: document).select(".listupd article, .listupd .bs")
            .mapNotNull { it.toSeriesCard() ?: it.toEpisodeCard() }
            .distinctBy { it.url.normalizeKey() }
    }

    private fun Element.toSeriesCard(): SearchResponse? {
        val anchor = selectFirst(".bsx a.tip[href], .bsx a[href], a.tip[href], h2 a[href], h3 a[href]") ?: return null
        val href = anchor.attr("href").toAbsoluteUrl() ?: return null
        if (!href.contains("/anime/", ignoreCase = true)) return null
        if (href.isBlockedTerm()) return null

        val poster = selectFirst("img")?.imageUrl()
            ?: anchor.selectFirst("img")?.imageUrl()
            ?: return null
        val title = cleanTitle(
            anchor.attr("title").cleanText().takeIf { it.length > 1 }
                ?: selectFirst(".tt h2, .tt, h2, h3")?.text()?.cleanText(),
        ) ?: return null
        if (title.isBlockedTerm()) return null

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    private fun Element.toEpisodeCard(): SearchResponse? {
        val anchor = selectFirst(".bsx a.tip[href], .bsx a[href], a.tip[href], h2 a[href], h3 a[href]") ?: return null
        val href = anchor.attr("href").toAbsoluteUrl() ?: return null
        if (href.contains("/anime/", ignoreCase = true)) return null
        if (!href.contains("episode", ignoreCase = true)) return null
        if (href.isBlockedTerm()) return null

        val poster = selectFirst("img")?.imageUrl()
            ?: anchor.selectFirst("img")?.imageUrl()
            ?: return null
        val title = cleanTitle(
            anchor.attr("title").cleanText().takeIf { it.length > 1 }
                ?: selectFirst(".tt h2, .tt, h2, h3")?.text()?.cleanText(),
        ) ?: return null
        if (title.isBlockedTerm()) return null

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    private fun parseEpisodes(document: Document): List<Episode> {
        return document.select(".eplister li a[href], .episodelist li a[href], .episode-list li a[href]").mapNotNull { anchor ->
            val href = anchor.attr("href").toAbsoluteUrl() ?: return@mapNotNull null
            if (!href.contains("episode", ignoreCase = true)) return@mapNotNull null
            if (href.isBlockedTerm()) return@mapNotNull null

            val rawTitle = anchor.selectFirst(".epl-title, .ep-title, .title, h3")?.text()
                ?: anchor.attr("title")
                ?: anchor.text()
            val title = cleanTitle(rawTitle) ?: return@mapNotNull null
            if (title.isBlockedTerm()) return@mapNotNull null

            val episodeNumber = anchor.selectFirst(".epl-num")?.text()?.toIntOrNull()
                ?: Regex("""(?i)(?:episode|eps?|ep)\s*(\d+)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""-(\d+)/?$""").find(href.trimEnd('/'))?.groupValues?.getOrNull(1)?.toIntOrNull()

            newEpisode(href) {
                this.name = title
                this.episode = episodeNumber
            }
        }
    }

    private fun collectStaticPlayers(document: Document, pageUrl: String): List<String> {
        val links = linkedSetOf<String>()
        document.select("#pembed iframe[src], .megavid iframe[src], .video-content iframe[src], iframe[src], embed[src], video[src], video source[src], source[src]").forEach { element ->
            element.attr("src").toAbsoluteUrl(pageUrl)?.let { links.add(it) }
        }
        return links.filter { it.isSupportedPlayerUrl() || it.isDirectMedia() }.distinct()
    }

    private fun collectMirrorPlayers(document: Document, pageUrl: String): List<String> {
        val links = linkedSetOf<String>()
        document.select("select.mirror option[value], .mirror option[value], select option[value]").forEach { option ->
            val rawValue = option.attr("value").trim()
            if (rawValue.isBlank()) return@forEach

            val decoded = decodeBase64(rawValue) ?: rawValue.decodeUrlLike()
            Jsoup.parse(decoded).select("iframe[src], embed[src], video[src], source[src], a[href]").forEach { element ->
                val value = element.attr("src").ifBlank { element.attr("href") }
                value.toAbsoluteUrl(pageUrl)?.let { links.add(it) }
            }
            collectScriptPlayers(decoded, pageUrl).forEach { links.add(it) }
        }
        return links.filter { it.isSupportedPlayerUrl() || it.isDirectMedia() }.distinct()
    }

    private fun collectScriptPlayers(raw: String, baseUrl: String): List<String> {
        val html = raw.decodeUrlLike()
        val links = linkedSetOf<String>()

        Jsoup.parse(html).select("iframe[src], embed[src], source[src], video[src], a[href]").forEach { element ->
            val value = element.attr("src").ifBlank { element.attr("href") }
            value.toAbsoluteUrl(baseUrl)?.let { links.add(it) }
        }

        val keyRegex = Regex("""(?i)(?:file|url|src|source|embed|embed_url|player|iframe)\s*[:=]\s*['"]([^'"]+)['"]""")
        keyRegex.findAll(html).forEach { match ->
            match.groupValues.getOrNull(1)?.toAbsoluteUrl(baseUrl)?.let { links.add(it) }
        }

        val urlRegex = Regex("""(?i)https?:\\?/\\?/[^'"<>\s]+""")
        urlRegex.findAll(html).forEach { match ->
            match.value.toAbsoluteUrl(baseUrl)?.let { links.add(it) }
        }

        return links.filter { it.isSupportedPlayerUrl() || it.isDirectMedia() }.distinct()
    }

    private fun detectStatus(document: Document): ShowStatus? {
        val text = document.select(".spe, .info-content, .bigcontent").text().lowercase()
        return when {
            "completed" in text || "complete" in text -> ShowStatus.Completed
            "ongoing" in text -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun looksEpisodePage(url: String, title: String): Boolean =
        !url.contains("/anime/", ignoreCase = true) || Regex("""(?i)episode\s*\d+""").containsMatchIn(title)

    private fun Document.isBlockedPage(): Boolean {
        val title = title().lowercase()
        val body = body()?.text()?.lowercase().orEmpty()
        return title.contains("just a moment") ||
            title.contains("attention required") ||
            title.contains("503 service") ||
            title.contains("service unavailable") ||
            body.contains("checking your browser") ||
            (body.contains("cloudflare") && body.contains("ray id")) ||
            body.contains("503 service unavailable")
    }

    private fun SearchResponse.isBlockedResult(): Boolean = url.isBlockedTerm()

    private fun cleanTitle(value: String?): String? {
        val text = value?.cleanText()?.takeIf { it.isNotBlank() } ?: return null
        return text
            .substringBefore(" - Jagoan Hentai", text)
            .substringBefore(" | Jagoan Hentai", text)
            .substringBefore(" – Jagoan Hentai", text)
            .replace(Regex("""(?i)\s+nonton\s+.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun Element.imageUrl(): String? = attr("data-src").ifBlank { attr("data-lazy-src") }
        .ifBlank { attr("data-original") }
        .ifBlank { attr("src") }
        .decodeUrlLike()
        .toAbsoluteUrl()

    private fun String.cleanText(): String = Jsoup.parse(this).text()
        .replace("\\n", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun String.decodeUrlLike(): String = trim()
        .replace("\\/", "/")
        .replace("&quot;", "\"")
        .replace("&#34;", "\"")
        .replace("&#039;", "'")
        .replace("&amp;", "&")
        .replace("&#038;", "&")
        .replace("\\u0026", "&")
        .replace("%3A", ":", ignoreCase = true)
        .replace("%2F", "/", ignoreCase = true)
        .replace("%3F", "?", ignoreCase = true)
        .replace("%26", "&", ignoreCase = true)
        .replace("%3D", "=", ignoreCase = true)
        .replace("%5B", "[", ignoreCase = true)
        .replace("%5D", "]", ignoreCase = true)

    private fun String?.toAbsoluteUrl(base: String = mainUrl): String? {
        val raw = this?.trim()?.trim('"', '\'', ' ')?.decodeUrlLike()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("/") -> mainUrl.trimEnd('/') + raw
            raw.startsWith("#") || raw.startsWith("javascript:", true) -> null
            else -> runCatching { URI(base).resolve(raw).toString() }.getOrNull()
        }
    }

    private fun String.isDirectMedia(): Boolean {
        val value = lowercase().substringBefore("#")
        return value.contains(".m3u8") ||
            value.contains(".mp4") ||
            value.contains(".webm") ||
            value.contains(".mkv") ||
            value.contains("cloudatacdn") ||
            value.contains("googlevideo.com/videoplayback")
    }

    private fun String.isSupportedPlayerUrl(): Boolean {
        val value = lowercase()
        if (!value.startsWith("http")) return false
        if (Regex("""\.(?:jpg|jpeg|png|webp|gif|svg|css|js|woff|woff2|ttf|ico)(?:\?|$)""").containsMatchIn(value)) return false
        return listOf(
            "playmogo", "streampoi", "filemoon", "streamtape", "streamruby", "dood", "d000d",
            "vidhide", "voe", "mixdrop", "mp4upload", "streamwish", "vidguard", "luluvdo",
            "sbembed", "blogger", "blogspot", "googlevideo", "cloudatacdn", "embed", "player",
        ).any { value.contains(it) }
    }

    private fun String.hostLabel(): String = runCatching { URI(this).host ?: name }
        .getOrDefault(name)
        .removePrefix("www.")
        .substringBefore('.')
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun decodeBase64(value: String): String? = runCatching {
        val normalized = value.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        base64Decode(padded)
    }.getOrNull()

    private fun String.normalizeKey(): String = trim().trimEnd('/').lowercase()

    private fun String.isBlockedTerm(): Boolean {
        val value = lowercase()
        return listOf(
            "loli", "lolicon", "shota", "shotacon", "underage", "minor", "child", "children", "kid", "kids",
        ).any { value.contains(it) }
    }
}
