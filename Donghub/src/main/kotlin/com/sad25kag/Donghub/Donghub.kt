package com.sad25kag.Donghub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Donghub : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://donghub.vip"
    override var name = "Donghub"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon
    )

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?order=update&page={page}" to "Rilisan Terbaru",
        "$mainUrl/anime/?status=completed&order=update&page={page}" to "Series Completed",
        "$mainUrl/anime/?type=movie&order=update&page={page}" to "Movie",
        "$mainUrl/anime/?type=ona&order=update&page={page}" to "ONA",
        "$mainUrl/genres/action/page/{page}/" to "Action",
        "$mainUrl/genres/adventure/page/{page}/" to "Adventure",
        "$mainUrl/genres/romance/page/{page}/" to "Romance"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            buildPagedUrl(request.data, page),
            headers = defaultHeaders,
            referer = "$mainUrl/"
        ).document

        val items = document.select("div.listupd > article, article.bs, .listupd article, .bs, .listo > article, .listo article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = document.select("a.next, .pagination a.next, a[href*='page=${page + 1}'], a[href*='/page/${page + 1}/']").isNotEmpty()

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("div.bsx > a[href], h2 > a[href], h3 > a[href], a[href]") ?: return null
        val href = fixUrl(anchor.attr("href"))

        if (href.isBlank()) return null
        if (href.contains("/genres/", true) ||
            href.contains("/genre/", true) ||
            href.contains("/tag/", true) ||
            href.contains("/page/", true) ||
            href.contains("/author/", true)
        ) return null

        val title = anchor.attr("title").trim()
            .ifBlank { selectFirst(".tt, h2, h3, .entry-title")?.text()?.trim().orEmpty() }
            .ifBlank { selectFirst("img")?.attr("alt")?.trim().orEmpty() }
            .ifBlank { anchor.text().trim() }
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (title.isBlank()) return null

        val poster = findPoster(this, href)
        val lowerText = (title + " " + text()).lowercase()
        val type = if (lowerText.contains("movie")) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, href, type) {
            posterUrl = poster
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val list = mutableListOf<SearchResponse>()
        val encoded = query.urlEncoded()

        var i = 1
        while (true) {
            val url = if (i == 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$i/?s=$encoded"
            val result = app.get(
                url,
                headers = defaultHeaders,
                referer = "$mainUrl/"
            ).document
                .select("div.listupd > article, article.bs, .listupd article, .bs")
                .mapNotNull { it.toSearchResult() }
                .filterNot { item -> list.any { it.url == item.url } }

            if (result.isEmpty()) break
            list.addAll(result)
            i++
        }

        return list
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url,
            headers = defaultHeaders,
            referer = "$mainUrl/"
        ).document

        val title = document.selectFirst("h1.entry-title, h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Judul Donghub tidak ditemukan")

        val detailRoot = findDetailRoot(document, title)
        val description = extractSynopsis(document, detailRoot, title)

        val infoText = detailRoot.selectFirst(".spe, .info-content, .infotable")
            ?.text()
            ?: document.selectFirst(".spe, .info-content, .infotable")
                ?.text()
            ?: detailRoot.text()

        val tags = extractDetailTags(document, detailRoot, title)

        var poster = document.selectFirst("div.ime > img, .thumb img, .bigcontent img")
            ?.let { findPoster(it, url) }
            ?: document.selectFirst("meta[property=og:image], meta[name=twitter:image]")
                ?.attr("content")
                ?.absoluteUrl(url)

        if (poster.isNullOrBlank()) {
            poster = findPoster(document.body(), url)
        }

        val recommendations = document.select("div.listupd article.bs, .listupd article, article.bs, .listo article")
            .mapNotNull { it.toRecommendResult() }
            .distinctBy { it.url }

        val isMovie = infoText.contains("Movie", true) || tags.any { it.equals("Movie", true) }

        val episodes = document
            .select(".eplister li > a[href], .episodelist li > a[href], div.list-episode .episode-item a[href], #episodes a[href]")
            .mapNotNull { it.toEpisodeOrNull(poster) }
            .distinctBy { it.data }

        return if (!isMovie && episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            val movieLink = episodes.firstOrNull()?.data ?: url
            newMovieLoadResponse(title, url, TvType.AnimeMovie, movieLink) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    private fun Element.toEpisodeOrNull(seriesPoster: String?): Episode? {
        val href = fixUrl(attr("href"))
        if (href.isBlank()) return null

        val epTitle = selectFirst(".epl-title, span, .epcur")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: text().trim().ifBlank { "Episode" }

        val episodeNumber = Regex("""(?i)(?:episode|eps|ep|e)\s*\.?\s*(\d{1,4})""")
            .find(epTitle)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""\b(\d{1,4})\b""")
                .find(epTitle)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        val epPoster = findPoster(this, href) ?: seriesPoster

        return newEpisode(href) {
            name = epTitle
            episode = episodeNumber
            posterUrl = epPoster
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = fixUrl(anchor.attr("href"))

        if (href.isBlank()) return null
        if (href.contains("/genres/", true) ||
            href.contains("/genre/", true) ||
            href.contains("/tag/", true) ||
            href.contains("/page/", true) ||
            href.contains("/author/", true)
        ) return null

        val title = selectFirst(".tt, h2, h3, .entry-title")?.text()?.trim()
            ?.ifBlank { anchor.attr("title").trim() }
            ?.ifBlank { selectFirst("img")?.attr("alt").orEmpty() }
            ?.ifBlank { anchor.text().trim() }
            ?: return null

        val poster = findPoster(this, href)

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
        }
    }

    private fun findDetailRoot(document: Element, title: String): Element {
        val candidates = document.select(
            ".bigcontent, .info-content, .entry-content-single, .entry-content, article, .postbody, .bixbox, main"
        )
            .filter { box ->
                val text = box.text()
                text.contains(title, true) &&
                    box.select("a[href*='/genres/']").isNotEmpty() &&
                    (
                        text.contains("Status:", true) ||
                            text.contains("Studio:", true) ||
                            text.contains("Type:", true) ||
                            text.contains("Episodes:", true)
                    )
            }
            .filterNot { it.tagName().equals("body", true) }

        candidates
            .filter { it.select("a[href*='/genres/']").size <= 8 }
            .minByOrNull { it.text().length }
            ?.let { return it }

        candidates
            .minByOrNull { it.text().length }
            ?.let { return it }

        val titleNode = document.select("h1.entry-title, h1")
            .firstOrNull { it.text().trim().equals(title, true) || it.text().contains(title, true) }

        var current = titleNode?.parent()
        while (current != null && !current.tagName().equals("body", true)) {
            val text = current.text()
            if (current.select("a[href*='/genres/']").isNotEmpty() &&
                (
                    text.contains("Status:", true) ||
                        text.contains("Studio:", true) ||
                        text.contains("Type:", true) ||
                        text.contains("Episodes:", true)
                )
            ) {
                return current
            }
            current = current.parent()
        }

        return document.body()
    }

    private fun extractDetailTags(document: Element, detailRoot: Element, title: String): List<String> {
        fun List<Element>.cleanTags(): List<String> {
            return map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()
        }

        val scoped = detailRoot.select("a[href*='/genres/']").cleanTags()
        if (scoped.isNotEmpty() && scoped.size <= 8) return scoped

        val titleNode = document.select("h1.entry-title, h1")
            .firstOrNull { it.text().trim().equals(title, true) || it.text().contains(title, true) }

        if (titleNode != null) {
            val candidates = mutableListOf<Element>()
            var item = titleNode.nextElementSibling()
            while (item != null) {
                val text = item.text().trim()
                if (text.startsWith("Watch streaming", true) ||
                    text.startsWith("Synopsis", true) ||
                    text.startsWith("History", true) ||
                    text.startsWith("Watch ", true)
                ) break

                if (item.select("a[href*='/genres/']").isNotEmpty()) {
                    candidates.addAll(item.select("a[href*='/genres/']"))
                }
                item = item.nextElementSibling()
            }

            candidates.cleanTags().takeIf { it.isNotEmpty() }?.let { return it }
        }

        return scoped.take(8)
    }

    private fun extractSynopsis(document: Element, detailRoot: Element, title: String): String? {
        val synopsisHeading = document.select("h2, h3, h4")
            .firstOrNull { it.text().trim().startsWith("Synopsis", true) }

        if (synopsisHeading != null) {
            val parts = mutableListOf<String>()
            var item = synopsisHeading.nextElementSibling()

            while (item != null) {
                val headingTag = item.tagName().matches(Regex("h[2-4]", RegexOption.IGNORE_CASE))
                val text = item.text().trim()

                if (headingTag ||
                    text.startsWith("History", true) ||
                    text.startsWith("Watch ", true) ||
                    text.startsWith("Comment", true)
                ) break

                text.cleanSynopsisText(title)?.let(parts::add)
                item = item.nextElementSibling()
            }

            parts.joinToString("\n\n")
                .cleanSynopsisText(title)
                ?.let { return it }
        }

        detailRoot.select("p, .synopsis, .sinopsis, .desc")
            .asSequence()
            .mapNotNull { it.text().cleanSynopsisText(title) }
            .firstOrNull()
            ?.let { return it }

        val markers = listOf("$title –", "$title -", "$title —")
        return listOf(detailRoot.text(), document.text())
            .asSequence()
            .mapNotNull { raw ->
                val marker = markers.firstOrNull { raw.contains(it, ignoreCase = true) }
                    ?: return@mapNotNull null
                val start = raw.indexOf(marker, ignoreCase = true)
                if (start < 0) return@mapNotNull null

                raw.substring(start)
                    .substringBeforeAny(
                        listOf(
                            " History",
                            " Watch $title",
                            " Related Episodes",
                            " Comment",
                            " Recommended Series"
                        )
                    )
                    .cleanSynopsisText(title)
            }
            .firstOrNull()
    }

    private fun String.cleanSynopsisText(title: String): String? {
        val value = replace(Regex("""\s+"""), " ")
            .trim()
            .removePrefix("Synopsis $title")
            .removePrefix("$title –")
            .removePrefix("$title -")
            .removePrefix("$title —")
            .trim()

        if (value.isBlank()) return null
        if (value.startsWith("Watch streaming", true)) return null
        if (value.startsWith("Watch full episodes", true)) return null
        if (value.contains("download free", true) && value.contains("Donghub", true)) return null

        return value
    }

    private fun String.substringBeforeAny(markers: List<String>): String {
        var result = this
        markers.forEach { marker ->
            val index = result.indexOf(marker, ignoreCase = true)
            if (index > 0) {
                result = result.substring(0, index)
            }
        }
        return result
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = defaultHeaders, referer = "$mainUrl/")
        val document = response.document
        val emittedDirect = linkedSetOf<String>()
        val visitedEmbeds = linkedSetOf<String>()
        var emittedVideoCount = 0

        val countedCallback: (ExtractorLink) -> Unit = { link ->
            emittedVideoCount++
            callback.invoke(link)
        }

        suspend fun emitDirect(rawUrl: String?, label: String = name) {
            val finalUrl = rawUrl
                ?.decodeEscaped()
                ?.absoluteUrl(data)
                ?.takeIf { it.isPlayableUrl() }
                ?: return

            if (!emittedDirect.add(finalUrl)) return

            countedCallback.invoke(
                newExtractorLink(
                    name,
                    label,
                    finalUrl,
                    INFER_TYPE
                ) {
                    referer = data
                    quality = getQualityFromName(label)
                    headers = streamHeaders(data)
                }
            )
        }

        suspend fun resolveEmbed(rawUrl: String?, label: String = name) {
            val fixedUrl = rawUrl
                ?.decodeEscaped()
                ?.absoluteUrl(data)
                ?.takeIf { it.isPlayableOrKnownEmbed() }
                ?: return

            if (fixedUrl.isPlayableUrl()) {
                emitDirect(fixedUrl, label)
                return
            }

            if (!visitedEmbeds.add(fixedUrl)) return

            runCatching {
                loadExtractor(fixedUrl, data, subtitleCallback, countedCallback)
            }
        }

        document.select(".mobius option, option[data-index], select option, .mirror option, .server option").forEach { item ->
            val value = item.attr("value").trim()
            if (value.isBlank()) return@forEach

            val label = item.text().trim().ifBlank { name }
            collectEmbedUrls(value, data).forEach { url ->
                resolveEmbed(url, label)
            }
        }

        val html = response.text
        collectEmbedUrls(html, data).forEach { url ->
            resolveEmbed(url, url.hostName())
        }

        val patterns = listOf(
            Regex("""file\s*[:=]\s*['"]([^'"]+\.(?:m3u8|mp4)[^'"]*)['"]""", RegexOption.IGNORE_CASE),
            Regex("""source\s*[:=]\s*['"]([^'"]+\.(?:m3u8|mp4)[^'"]*)['"]""", RegexOption.IGNORE_CASE),
            Regex("""['"](https?://[^'"]+\.(?:m3u8|mp4)[^'"]*)['"]""", RegexOption.IGNORE_CASE)
        )

        patterns.forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                emitDirect(match.groupValues.getOrNull(1), "$name - Direct")
            }
        }

        return emittedVideoCount > 0
    }

    private fun collectEmbedUrls(raw: String, pageUrl: String): List<String> {
        val candidates = mutableListOf<String>()
        val value = raw.trim().decodeEscaped()

        if (value.isNotBlank()) {
            if (value.startsWith("http", true) || value.startsWith("//")) {
                candidates.add(value.absoluteUrl(pageUrl))
            }

            extractEmbedUrls(value, pageUrl).let(candidates::addAll)

            runCatching { base64Decode(value) }
                .getOrNull()
                ?.decodeEscaped()
                ?.let { decoded ->
                    if (decoded.startsWith("http", true) || decoded.startsWith("//")) {
                        candidates.add(decoded.absoluteUrl(pageUrl))
                    }
                    extractEmbedUrls(decoded, pageUrl).let(candidates::addAll)
                }
        }

        return candidates
            .map { it.absoluteUrl(pageUrl) }
            .filter { it.isPlayableOrKnownEmbed() }
            .distinct()
    }

    private fun extractEmbedUrls(fragment: String, pageUrl: String): List<String> {
        val candidates = mutableListOf<String>()
        val doc = Jsoup.parse(fragment)

        doc.select("iframe[src], source[src], video[src], a[href], meta[itemprop=embedUrl], meta[property=og:video], meta[property=og:video:url]").forEach { item ->
            val rawUrl = item.attr("src")
                .ifBlank { item.attr("href") }
                .ifBlank { item.attr("content") }
                .trim()

            if (rawUrl.isNotBlank()) {
                candidates.add(rawUrl.absoluteUrl(pageUrl))
            }
        }

        val urlRegex = Regex(
            """(?i)(?:https?:)?//[^'"\s<>]+(?:dailymotion\.com|geo\.dailymotion\.com|ok\.ru|okru|rpmvid\.com|dmcdn\.net)[^'"\s<>]*|https?://[^'"\s<>]+\.(?:m3u8|mp4)[^'"\s<>]*"""
        )

        urlRegex.findAll(fragment).forEach { match ->
            candidates.add(match.value.absoluteUrl(pageUrl))
        }

        return candidates.distinct()
    }

    private fun findPoster(element: Element, pageUrl: String): String? {
        val boxes = listOfNotNull(
            element,
            element.parent(),
            element.parent()?.parent(),
            element.parent()?.parent()?.parent()
        ).distinct()

        for (box in boxes) {
            extractImage(box, pageUrl)?.let { return it }
            box.select("img, source, div, span, a").forEach { child ->
                extractImage(child, pageUrl)?.let { return it }
            }
        }

        return null
    }

    private fun extractImage(element: Element, pageUrl: String): String? {
        val attrs = listOf(
            "data-src",
            "data-lazy-src",
            "data-original",
            "data-image",
            "data-img",
            "src",
            "poster"
        )

        attrs.forEach { attr ->
            val value = element.attr(attr).trim()
            if (value.isImageCandidate()) return value.absoluteUrl(pageUrl)
        }

        listOf("srcset", "data-srcset").forEach { attr ->
            val src = element.attr(attr)
                .split(",")
                .map { it.trim().substringBefore(" ").trim() }
                .firstOrNull { it.isImageCandidate() }

            if (!src.isNullOrBlank()) return src.absoluteUrl(pageUrl)
        }

        val style = element.attr("style")
        Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
            .find(style)
            ?.groupValues
            ?.getOrNull(2)
            ?.trim()
            ?.takeIf { it.isImageCandidate() }
            ?.let { return it.absoluteUrl(pageUrl) }

        return null
    }

    private fun String.isImageCandidate(): Boolean {
        if (isBlank()) return false
        if (startsWith("data:", true)) return false
        if (contains("blank", true) || contains("placeholder", true) || contains("spacer", true)) return false
        return contains(".jpg", true) ||
            contains(".jpeg", true) ||
            contains(".png", true) ||
            contains(".webp", true) ||
            contains("/wp-content/uploads/", true)
    }

    private fun String.isPlayableUrl(): Boolean {
        return contains(".m3u8", true) ||
            contains(".mp4", true) ||
            contains("dmcdn.net", true)
    }

    private fun String.isPlayableOrKnownEmbed(): Boolean {
        return isPlayableUrl() ||
            contains("dailymotion.com", true) ||
            contains("geo.dailymotion.com", true) ||
            contains("ok.ru", true) ||
            contains("okru", true) ||
            contains("rpmvid.com", true)
    }

    private fun buildPagedUrl(rawUrl: String, page: Int): String {
        if (!rawUrl.contains("{page}")) {
            if (page <= 1) return rawUrl
            val clean = rawUrl.trimEnd('/')
            return when {
                clean.contains("?") -> "$clean&page=$page"
                else -> "$clean/page/$page/"
            }
        }

        return if (page <= 1) {
            rawUrl
                .replace("/page/{page}/", "/")
                .replace("/page/{page}", "/")
                .replace("page={page}", "page=1")
        } else {
            rawUrl.replace("{page}", page.toString())
        }
    }

    private fun String.absoluteUrl(baseUrl: String): String {
        val value = trim().decodeEscaped()
        return when {
            value.isBlank() -> ""
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> {
                val base = baseUrl.substringBeforeLast("/", mainUrl)
                "$base/$value"
            }
        }
    }

    private fun String.decodeEscaped(): String {
        return replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("&amp;", "&")
    }

    private fun String.urlEncoded(): String = runCatching {
        URLEncoder.encode(this, "UTF-8")
    }.getOrDefault(this)

    private fun String.hostName(): String {
        return runCatching {
            substringAfter("://")
                .substringBefore("/")
                .substringBeforeLast(".")
                .substringAfterLast(".")
        }.getOrDefault(name)
    }

    private fun streamHeaders(refererUrl: String): Map<String, String> {
        return mapOf(
            "Accept" to "*/*",
            "User-Agent" to USER_AGENT,
            "Referer" to refererUrl,
            "Origin" to mainUrl
        )
    }

    private val defaultHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )
}
