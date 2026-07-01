package com.sad25kag.drakorid
import android.util.Base64
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
class DrakoridProvider : MainAPI() {
    override var mainUrl = "https://drakorid.cam"
    override var name = "Drakor.id"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)
    private val watchBaseUrl = "https://drakorid.co"
    private val baseHeaders = mapOf(
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    )
    override val mainPage = mainPageOf(
        "" to "Latest Release",
        "series/?order=update&status=&type=" to "Series Update",
        "series/?order=popular&status=&type=" to "Popular",
        "genres/romance" to "Romance",
        "genres/comedy" to "Comedy",
        "genres/action" to "Action",
        "genres/fantasy" to "Fantasy",
        "genres/historical" to "Historical",
        "genres/thriller" to "Thriller",
        "genres/mystery" to "Mystery",
        "genres/horror" to "Horror"
    )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            buildPageUrl(request.data, page),
            headers = baseHeaders,
            referer = mainUrl
        ).document
        val allowEpisodeCards = request.data.isBlank() || request.data.contains("order=update", true)
        val items = document.extractSearchResults(allowEpisodeCards = allowEpisodeCards)
            .distinctBy { it.url }
            .take(40)
        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = page == 1 && items.isNotEmpty() && document.select("a.next, .pagination a, a[href*='/page/']").isNotEmpty()
        )
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val document = app.get(
            "$mainUrl/?s=$encoded",
            headers = baseHeaders,
            referer = mainUrl
        ).document
        return document.extractSearchResults(allowEpisodeCards = true)
            .filter { it.name.contains(clean, ignoreCase = true) || clean.split(" ").any { part -> it.name.contains(part, ignoreCase = true) } }
            .distinctBy { it.url }
    }
    override suspend fun load(url: String): LoadResponse {
        val sourceDocument = app.get(
            url,
            headers = baseHeaders,
            referer = mainUrl
        ).document
        val sourceTitle = sourceDocument.extractDetailTitle()
            ?: throw ErrorLoadingException("Judul Drakor.id tidak ditemukan")
        val sourceSeriesUrl = sourceDocument.findSeriesUrl()
        val detailDocument = if (url.contains("episode", true) && sourceSeriesUrl != null) {
            runCatching {
                app.get(sourceSeriesUrl, headers = baseHeaders, referer = url).document
            }.getOrDefault(sourceDocument)
        } else {
            sourceDocument
        }
        val title = detailDocument.extractDetailTitle() ?: sourceTitle
        val seriesUrl = detailDocument.findSeriesUrl() ?: sourceSeriesUrl
        val seriesSlug = seriesUrl?.substringAfter("/series/")?.substringBefore("/")?.takeIf { it.isNotBlank() }
        val seriesTitle = detailDocument.select("a[href*='/series/']")
            .firstOrNull { it.attr("abs:href") == seriesUrl }
            ?.text()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: title.substringBefore(" Episode ").trim()
        val poster = detailDocument.extractPosterUrl() ?: sourceDocument.extractPosterUrl()
        val tags = detailDocument.extractDetailTags()
        val castNames = detailDocument.extractCastNames()
        val actors = castNames.map { ActorData(Actor(it)) }
        val year = Regex("""\((\d{4})\)""").find(seriesTitle.ifBlank { title })?.groupValues?.getOrNull(1)?.toIntOrNull()
        val plot = detailDocument.extractDetailPlot()
        val episodes = detailDocument.select("a[href]")
            .mapNotNull { element ->
                val href = element.attr("abs:href").ifBlank { fixUrl(element.attr("href")) }
                if (!href.startsWith(mainUrl) || !href.contains("episode", ignoreCase = true)) return@mapNotNull null
                if (seriesSlug != null && !href.contains(seriesSlug, ignoreCase = true)) return@mapNotNull null
                val epNo = extractEpisodeNumber(href, element.text()) ?: return@mapNotNull null
                Triple(epNo, href, element.text().cleanTitle().ifBlank { "Episode $epNo" })
            }
            .distinctBy { it.second }
            .sortedBy { it.first }
            .map { (epNo, epUrl, epName) ->
                newEpisode(epUrl) {
                    name = epName
                    episode = epNo
                }
            }
        val isMovie = url.contains("/movie/", true) ||
            detailDocument.text().contains("Type: Movie", true) ||
            (episodes.isEmpty() && !title.contains("Episode", true))
        if (isMovie || episodes.isEmpty()) {
            val movieData = detailDocument.findWatchLiteUrl(url) ?: sourceDocument.findWatchLiteUrl(url) ?: buildWatchLiteUrl(url) ?: url
            return newMovieLoadResponse(seriesTitle.ifBlank { title }, url, if (isMovie) TvType.Movie else TvType.AsianDrama, movieData) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                if (actors.isNotEmpty()) this.actors = actors
            }
        }
        return newTvSeriesLoadResponse(seriesTitle.ifBlank { title }, url, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            if (actors.isNotEmpty()) this.actors = actors
        }
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = resolveEpisodeData(data) ?: return false
        var loaded = false
        val guardedCallback: (ExtractorLink) -> Unit = { link ->
            loaded = true
            callback.invoke(link)
        }
        val pages = linkedSetOf(pageUrl)
        if (!pageUrl.contains("episode", true) && !pageUrl.contains("/watch-lite/", true) && !pageUrl.contains("/player/bunny.php", true)) {
            buildWatchLiteUrl(pageUrl)?.let { pages.add(it) }
        }
        pages.forEach { currentUrl ->
            if (currentUrl.contains("/player/bunny.php", true)) {
                if (extractBunnyPlayer(currentUrl, watchBaseUrl, guardedCallback)) return@forEach
            }
            val referer = when {
                currentUrl.startsWith(watchBaseUrl, true) -> watchBaseUrl
                else -> mainUrl
            }
            val document = runCatching {
                app.get(currentUrl, headers = baseHeaders, referer = referer).document
            }.getOrNull() ?: return@forEach
            val candidates = linkedSetOf<String>()
            if (!currentUrl.contains("/watch-lite/", true)) {
                document.findWatchLiteUrl(currentUrl)?.let { candidates.add(it) }
            }
            candidates += document.select("iframe[src], video source[src], video[src]")
                .mapNotNull { it.attr("abs:src").ifBlank { it.attr("abs:href") }.takeIf { url -> url.startsWith("http") } }
            document.select("option[value], [data-video], [data-src], [data-url], [data-embed], a[href]").forEach { element ->
                listOf("value", "data-video", "data-src", "data-url", "data-embed", "href").forEach attrs@{ attr ->
                    val raw = element.attr(attr).trim()
                    if (raw.isBlank() || raw == "#" || raw.equals("javascript:;", true)) return@attrs
                    val decoded = decodeServerPayload(raw)
                    candidates += extractUrls(decoded, currentUrl)
                    if (decoded.startsWith("http")) candidates += decoded
                }
            }
            candidates += extractUrls(document.html(), currentUrl)
            candidates
                .map { it.trim().replace("\\/", "/") }
                .filter { it.startsWith("http") && it.isPlayableCandidate() }
                .distinct()
                .forEach candidateLoop@{ url ->
                    when {
                        url.contains("/watch-lite/", true) -> {
                            val watchDocument = runCatching {
                                app.get(url, headers = baseHeaders, referer = watchBaseUrl).document
                            }.getOrNull() ?: return@candidateLoop
                            watchDocument.select("iframe[src*='bunny.php'], a[href*='bunny.php'], iframe[src], a[href]")
                                .mapNotNull { it.attr("abs:src").ifBlank { it.attr("abs:href") }.takeIf { link -> link.startsWith("http") } }
                                .filter { it.contains("/player/bunny.php", true) || it.isPlayableCandidate() }
                                .distinct()
                                .forEach { playerUrl ->
                                    if (playerUrl.contains("/player/bunny.php", true)) {
                                        extractBunnyPlayer(playerUrl, watchBaseUrl, guardedCallback)
                                    } else if (playerUrl.contains(".m3u8", true) || playerUrl.contains(".mp4", true)) {
                                        emitDirect(playerUrl, url, guardedCallback)
                                    } else {
                                        loadExtractor(playerUrl, url, subtitleCallback, guardedCallback)
                                    }
                                }
                        }
                        url.contains("/player/bunny.php", true) -> {
                            extractBunnyPlayer(url, watchBaseUrl, guardedCallback)
                        }
                        url.contains("seekplayer.vip", true) -> {
                            if (!extractSeekPlayer(url, currentUrl, guardedCallback)) {
                                loadExtractor(url, currentUrl, subtitleCallback, guardedCallback)
                            }
                        }
                        url.contains(".m3u8", true) || url.contains(".mp4", true) -> {
                            emitDirect(url, currentUrl, guardedCallback)
                        }
                        else -> {
                            loadExtractor(url, currentUrl, subtitleCallback, guardedCallback)
                        }
                    }
                }
        }
        return loaded
    }
    private fun Document.extractSearchResults(allowEpisodeCards: Boolean = true): List<SearchResponse> {
        return collectSearchResults(allowEpisodeCards = allowEpisodeCards)
    }
    private fun Document.collectSearchResults(allowEpisodeCards: Boolean): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = linkedSetOf<String>()
        val selector = if (allowEpisodeCards) {
            "article, div.bs, div.listupd .bs, div.item, div.card, .swiper-slide, .listupd a[href], .excstf a[href], a[href*='/series/'], a[href*='/movie/'], a[href*='episode']"
        } else {
            "article, div.bs, div.listupd .bs, div.item, div.card, .swiper-slide, .listupd a[href], .excstf a[href], a[href*='/series/'], a[href*='/movie/']"
        }
        select(selector).forEach { element ->
            val response = element.toSearchResult(allowEpisodeCards) ?: return@forEach
            if (seen.add(response.url)) results.add(response)
        }
        return results
    }
    private fun Element.toSearchResult(allowEpisodeCards: Boolean): SearchResponse? {
        val linkEl = when {
            tagName().equals("a", true) -> this
            allowEpisodeCards -> selectFirst("a[href*='/series/'], a[href*='/movie/'], a[href*='episode'], a[href]")
            else -> selectFirst("a[href*='/series/'], a[href*='/movie/'], a[href]")
        } ?: return null
        val href = fixUrl(linkEl.attr("abs:href").ifBlank { linkEl.attr("href") })
        if (!href.startsWith(mainUrl)) return null
        if (href == mainUrl || href.contains("/genres/", true) || href.contains("/tag/", true) || href.contains("/category/", true)) return null
        if (!allowEpisodeCards && href.contains("episode", true)) return null
        val rawTitle = selectFirst("h2, h3, h4, h5, .tt, .name, .entry-title, .post-title")?.text() ?: linkEl.attr("title").ifBlank { linkEl.text() }
        val title = rawTitle.cleanTitle().takeIf { it.isNotBlank() } ?: return null
        if (title.length < 3 || title.equals("Search", true) || title.equals("Home", true)) return null
        val poster = extractImageUrl()
        val isMovie = href.contains("/movie/", true) || text().contains("Movie", true)
        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { this.posterUrl = poster }
        }
    }
    private fun buildPageUrl(path: String, page: Int): String {
        val clean = path.trim().trim('/')
        val base = when {
            clean.isBlank() -> mainUrl
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            else -> "$mainUrl/$clean"
        }
        if (page <= 1) return base
        return if (base.contains("?")) {
            base.replaceBefore("?", base.substringBefore("?").trimEnd('/') + "/page/$page")
        } else {
            "${base.trimEnd('/')}/page/$page/"
        }
    }
    private fun resolveEpisodeData(data: String): String? {
        val clean = data.trim()
        if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) return clean
        return runCatching {
            val json = JSONObject(clean)
            val url = json.optString("url").takeIf { it.isNotBlank() }
            if (url != null) return@runCatching fixUrl(url)
            val slug = json.optString("slug")
            val episode = json.optInt("episode", 0)
            when {
                slug.startsWith("http", true) -> slug
                slug.isNotBlank() && episode > 0 -> "$mainUrl/${slug.trim('/')}-episode-$episode/"
                slug.isNotBlank() -> "$mainUrl/${slug.trim('/')}/"
                else -> null
            }
        }.getOrNull()
    }
    private suspend fun extractSeekPlayer(
        iframeSrc: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = iframeSrc.substringAfter("#", "").substringBefore("?").trim()
        if (id.isBlank()) return false
        val host = runCatching { URI(iframeSrc).host }.getOrNull() ?: return false
        val endpoints = listOf(
            "https://$host/api/v1/video?id=$id&w=421&h=935&r=drakorid.cam",
            "https://$host/api/v1/video?id=$id&w=1920&h=1080&r=drakorid.cam",
            "https://$host/api/v1/info?id=$id"
        )
        endpoints.forEach { apiUrl ->
            val decrypted = runCatching {
                val hexResponse = app.get(apiUrl, referer = iframeSrc, headers = baseHeaders).text.trim()
                if (!hexResponse.matches(Regex("^[0-9a-fA-F]+$"))) return@runCatching null
                decryptSeekPlayerHex(hexResponse)
            }.getOrNull() ?: return@forEach
            val normalized = decrypted.replace("\\/", "/")
            val mediaUrl = runCatching {
                val json = JSONObject(normalized)
                listOf(
                    json.optString("source"),
                    json.optString("cf")
                ).firstOrNull { it.contains(".m3u8", true) || it.contains(".mp4", true) }
            }.getOrNull()
                ?: Regex("""https?://[^\"'\s<>]+?(?:\.m3u8|\.mp4)[^\"'\s<>]*""")
                    .find(normalized)
                    ?.value
            if (!mediaUrl.isNullOrBlank()) {
                emitDirect(mediaUrl, iframeSrc, callback)
                return true
            }
        }
        return false
    }
    private suspend fun extractBunnyPlayer(
        playerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rawVideo = playerUrl.substringAfter("v=", "").substringBefore("&").takeIf { it.isNotBlank() } ?: return false
        val decoded = decodeServerPayload(rawVideo).replace("\\/", "/")
        val mediaUrl = decoded.takeIf { it.startsWith("http") && (it.contains(".m3u8", true) || it.contains(".mp4", true)) }
            ?: extractUrls(decoded, playerUrl).firstOrNull { it.contains(".m3u8", true) || it.contains(".mp4", true) }
            ?: return false
        emitDirect(mediaUrl, "$referer/", callback)
        return true
    }
    private fun decryptSeekPlayerHex(hex: String): String {
        val secretKey = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
        val ivBytes = "1234567890oiuytr".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secretKey, "AES"), IvParameterSpec(ivBytes))
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return String(cipher.doFinal(bytes), Charsets.UTF_8)
    }
    private suspend fun emitDirect(
        mediaUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = mediaUrl.replace("\\/", "/")
        val type = if (fixedUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        val quality = getQualityFromName(fixedUrl).let {
            if (it == Qualities.Unknown.value) inferQuality(fixedUrl) else it
        }
        val cleanReferer = referer.trimEnd('/') + "/"
        val origin = if (fixedUrl.contains("hls.drakor.cc", true)) {
            watchBaseUrl
        } else {
            mainUrl
        }
        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name ${qualityLabel(quality)}",
                url = fixedUrl,
                type = type
            ) {
                this.quality = quality
                this.referer = cleanReferer
                this.headers = mapOf(
                    "Referer" to cleanReferer,
                    "Origin" to origin,
                    "User-Agent" to (baseHeaders["User-Agent"] ?: "Mozilla/5.0")
                )
            }
        )
    }
    private fun decodeServerPayload(raw: String): String {
        val decodedUrl = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        val candidates = listOf(raw, decodedUrl, decodedUrl.substringAfter("base64,", decodedUrl))
        candidates.forEach { candidate ->
            val clean = candidate.trim().trim('\'', '"')
            if (clean.length < 8 || !clean.matches(Regex("^[A-Za-z0-9+/=_-]+$"))) return@forEach
            listOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP).forEach { flag ->
                val decoded = runCatching { String(Base64.decode(clean, flag), Charsets.UTF_8) }.getOrNull()
                if (!decoded.isNullOrBlank() && (decoded.contains("http") || decoded.contains("<iframe", true))) {
                    return decoded
                }
            }
        }
        return decodedUrl
    }
    private fun String.isPlayableCandidate(): Boolean {
        return contains("/watch-lite/", true) ||
            contains("/player/bunny.php", true) ||
            contains("seekplayer.vip", true) ||
            contains("abyssplayer.com", true) ||
            contains("vidmoly", true) ||
            contains("dailymotion.com", true) ||
            contains("streamtape", true) ||
            contains("filemoon", true) ||
            contains("mp4upload", true) ||
            contains("hls.drakor.cc", true) ||
            contains(".m3u8", true) ||
            contains(".mp4", true)
    }
    private fun extractUrls(text: String, pageUrl: String): Set<String> {
        val normalized = text.replace("\\/", "/")
        val urls = linkedSetOf<String>()
        Regex("""https?://[^\"'\\s<>]+""")
            .findAll(normalized)
            .forEach { urls.add(it.value.trim().trim('"', '\'', ',', ';', ')', ']')) }
        Regex("""(?:src|href)=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { fixUrl(it, pageUrl) }
            .filter { it.startsWith("http") }
            .forEach { urls.add(it) }
        return urls
    }
    private fun Document.findWatchLiteUrl(pageUrl: String): String? {
        return select("a[href*='/watch-lite/'], iframe[src*='/watch-lite/'], a[href*='/nonton/']")
            .mapNotNull { element ->
                element.attr("abs:href")
                    .ifBlank { element.attr("abs:src") }
                    .ifBlank { element.attr("href") }
                    .ifBlank { element.attr("src") }
                    .takeIf { it.isNotBlank() }
                    ?.let { fixUrl(it, pageUrl).replace(mainUrl, watchBaseUrl) }
            }
            .firstOrNull { it.contains("/watch-lite/", true) }
            ?: buildWatchLiteUrl(pageUrl)
    }
    private fun buildWatchLiteUrl(url: String): String? {
        val slug = url.trimEnd('/')
            .substringAfterLast('/')
            .substringBefore('?')
            .takeIf { it.isNotBlank() && !it.contains("episode", true) && it != "series" && it != "movie" }
            ?: return null
        return "$watchBaseUrl/watch-lite/$slug/1"
    }
    private fun Document.extractDetailTitle(): String? {
        return selectFirst("h1, .entry-title, .post-title, meta[property=og:title]")
            ?.let { it.attr("content").ifBlank { it.text() } }
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
    }
    private fun Document.findSeriesUrl(): String? {
        return select("a[href*='/series/']")
            .mapNotNull { it.attr("abs:href").ifBlank { it.attr("href") }.takeIf { href -> href.isNotBlank() } }
            .map { fixUrl(it) }
            .firstOrNull { it.startsWith(mainUrl) && it.contains("/series/", true) }
    }
    private fun Document.extractDetailTags(): List<String> {
        val selectors = listOf(
            ".genxed a[href*='/genres/']",
            ".mgen a[href*='/genres/']",
            ".genre a[href*='/genres/']",
            ".seriestucon a[href*='/genres/']",
            ".spe a[href*='/genres/']",
            "article .genxed a",
            "article .mgen a"
        )
        selectors.forEach { selector ->
            val tags = select(selector)
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()
            if (tags.isNotEmpty()) return tags
        }
        return emptyList()
    }
    private fun Document.extractDetailPlot(): String? {
        select("h2, h3, h4").firstOrNull { it.text().contains("Synopsis", true) }?.let { heading ->
            val parts = mutableListOf<String>()
            var cursor = heading.nextElementSibling()
            while (cursor != null) {
                val tag = cursor.tagName().lowercase()
                val text = cursor.text().trim()
                if (tag.matches(Regex("h[1-6]")) || text.equals("History", true) || text.startsWith("Watch ", true)) break
                val texts = cursor.select("p")
                    .map { it.text().trim() }
                    .ifEmpty { listOf(text) }
                texts.filter { it.isUsefulPlotText() }.forEach { parts.add(it) }
                if (parts.joinToString(" ").length > 900) break
                cursor = cursor.nextElementSibling()
            }
            parts.joinToString("\n")
                .trim()
                .takeIf { it.isUsefulPlotText() }
                ?.let { return it }
        }
        return select(".synopsis p, [itemprop=description], .desc p")
            .map { it.text().trim() }
            .firstOrNull { it.isUsefulPlotText() }
    }
    private fun Document.extractCastNames(): List<String> {
        val detailText = select(".spe, .seriestucon, .info, .entry-content, article")
            .joinToString(" ") { it.text() }
            .replace(Regex("""\s+"""), " ")
        val castText = Regex(
            """(?i)\bCasts?\s*:\s*(.+?)(?:\s+Posted by:|\s+Released on:|\s+Updated on:|\s+Genres?:|\s+Synopsis\b|$)"""
        ).find(detailText)?.groupValues?.getOrNull(1)
        return castText
            ?.split(",")
            ?.map { it.cleanTitle().trim() }
            ?.map { it.replace(Regex("""\s+"""), " ") }
            ?.filter { it.isNotBlank() && !it.contains(":") && it.length <= 60 }
            ?.distinct()
            .orEmpty()
    }
    private fun String.isUsefulPlotText(): Boolean {
        val clean = trim()
        if (clean.length < 20) return false
        val lower = clean.lowercase()
        return !lower.startsWith("watch streaming ") &&
            !lower.startsWith("watch full episodes ") &&
            !lower.startsWith("download ") &&
            !lower.contains("don't forget to watch") &&
            !lower.contains("don't forget to click") &&
            !lower.contains("save internet quota") &&
            !lower.contains("hardsub softsub")
    }
    private fun Document.extractPosterUrl(): String? {
        val selectors = listOf(
            "meta[property=og:image]",
            "meta[name=twitter:image]",
            ".thumb img",
            ".poster img",
            ".entry-content img",
            "article img",
            "img[src*='wp-content']",
            "img[data-src*='wp-content']",
            "img[data-lazy-src*='wp-content']"
        )
        selectors.forEach { selector ->
            selectFirst(selector)?.extractImageUrl()?.let { return it }
        }
        return null
    }
    private fun Element.extractImageUrl(): String? {
        extractOwnImageUrl()?.let { return it }
        select("img, meta[property=og:image], meta[name=twitter:image]")
            .asSequence()
            .mapNotNull { it.extractOwnImageUrl() }
            .firstOrNull()
            ?.let { return it }
        return null
    }
    private fun Element.extractOwnImageUrl(): String? {
        val attrs = listOf(
            "content",
            "abs:src",
            "src",
            "abs:data-src",
            "data-src",
            "abs:data-lazy-src",
            "data-lazy-src",
            "abs:data-original",
            "data-original"
        )
        attrs.forEach { attrName ->
            val value = attr(attrName).trim()
            if (value.isNotBlank()) normalizeImageUrl(value)?.let { return it }
        }
        listOf(attr("srcset"), attr("data-srcset")).forEach { srcset ->
            srcset.split(",")
                .map { it.trim().substringBefore(" ").trim() }
                .firstOrNull { it.isNotBlank() }
                ?.let { normalizeImageUrl(it) }
                ?.let { return it }
        }
        return null
    }
    private fun normalizeImageUrl(url: String): String? {
        val clean = url.trim().trim('"', '\'', ' ')
        if (clean.isBlank() || clean.startsWith("data:", true) || clean.contains("placeholder", true)) return null
        val fixed = fixUrl(clean).replace("\\/", "/")
        val wpProxy = Regex("""https?://i\d+\.wp\.com/(drakorid\.(?:cam|co)/.+)""", RegexOption.IGNORE_CASE)
            .find(fixed)
            ?.groupValues
            ?.getOrNull(1)
            ?.substringBefore("?")
        return if (!wpProxy.isNullOrBlank()) "https://$wpProxy" else fixed
    }
    private fun fixUrl(url: String, referer: String = mainUrl): String {
        val clean = url.trim()
        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            clean.startsWith("/") -> "${originFromUrl(referer) ?: mainUrl}$clean"
            clean.isBlank() -> clean
            else -> referer.substringBeforeLast("/") + "/" + clean
        }
    }
    private fun originFromUrl(url: String): String? {
        return runCatching {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        }.getOrNull()
    }
    private fun extractEpisodeNumber(href: String, text: String): Int? {
        return Regex("episode[-\\s]*(\\d+)", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?:Eps|Episode|Ep)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
    private fun inferQuality(url: String): Int {
        return when {
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
    private fun qualityLabel(quality: Int): String {
        return if (quality == Qualities.Unknown.value) "Auto" else "${quality}p"
    }
    private fun String.cleanTitle(): String {
        return replace(Regex("""\s+"""), " ")
            .replace(Regex("""(?i)\s+[-–]\s+drakor\.id.*$"""), "")
            .replace(Regex("""(?i)^Nonton\s+"""), "")
            .trim()
    }
}