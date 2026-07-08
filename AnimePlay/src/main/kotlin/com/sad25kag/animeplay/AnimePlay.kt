package com.sad25kag.animeplay

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class AnimePlay : MainAPI() {

    override var mainUrl   = "https://anime-play.id"
    override var name      = "AnimePlay"
    override var lang      = "id"
    override val hasMainPage          = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // ─── Main page sections ───────────────────────────────────────────────────
    // Evidence from active menu: /tag/on-going, /tag/complete, /type/movie, /type/ova, /type/ona

    override val mainPage = mainPageOf(
        "$mainUrl/tag/on-going" to "On-Going",
        "$mainUrl/tag/complete" to "Complete",
        "$mainUrl/type/movie"   to "Movie",
        "$mainUrl/type/ova"     to "OVA",
        "$mainUrl/type/ona"     to "ONA",
    )

    // ─── Poster helper ────────────────────────────────────────────────────────

    private fun toPosterUrl(src: String?): String? {
        if (src.isNullOrBlank()) return null
        return when {
            src.startsWith("http") -> src
            src.startsWith("//")   -> "https:$src"
            src.startsWith("/")    -> "$mainUrl$src"
            else                   -> null
        }
    }

    // ─── Card parser (listing + search pages) ────────────────────────────────
    // Evidence:
    //   - DOM: <a href="/anime/{slug}"> contains <img src="/_wp_images/{hash}.webp" alt="Title">
    //   - Fallback: JSON-LD @type=ItemList with name+url per entry

    private fun parseCards(
        rawHtml: String,
        doc: org.jsoup.nodes.Document,
    ): List<AnimeSearchResponse> {
        val results = mutableListOf<AnimeSearchResponse>()
        val seen    = mutableSetOf<String>()

        // DOM pass — every <a href="/anime/{slug}"> that is NOT an episode link
        for (link in doc.select("a[href]")) {
            val href = link.attr("href").trim()
            val isAnimeDetail = (href.startsWith("/anime/") || href.startsWith("$mainUrl/anime/"))
                && !href.contains("/episode/")
                && href != "/anime" && href != "/anime/"
            if (!isAnimeDetail) continue

            val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            if (!seen.add(fullUrl)) continue

            val img    = link.selectFirst("img[src]")
            val title  = img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: link.text().trim().takeIf { it.isNotBlank() }
                ?: continue
            val poster = toPosterUrl(img?.attr("src").orEmpty().takeIf { it.isNotBlank() })

            val tvType = when {
                fullUrl.contains("-movie", ignoreCase = true) ||
                title.contains("Movie",   ignoreCase = true)  -> TvType.AnimeMovie
                fullUrl.contains("/ova",  ignoreCase = true)  ||
                title.contains(" OVA",   ignoreCase = true)   -> TvType.OVA
                else                                           -> TvType.Anime
            }
            results.add(newAnimeSearchResponse(title, fullUrl, tvType) {
                this.posterUrl = poster
            })
        }

        // Fallback — JSON-LD ItemList (no posters but reliable titles+URLs)
        if (results.isEmpty()) {
            val m = Regex(
                """"@type"\s*:\s*"ItemList"[\s\S]*?"itemListElement"\s*:\s*(\[[\s\S]*?])\s*"""
            ).find(rawHtml)
            val listBlock = m?.groupValues?.getOrNull(1) ?: return results
            val entryRe   = Regex(""""name"\s*:\s*"([^"]+)"[\s\S]*?"url"\s*:\s*"([^"]+)"""")
            for (em in entryRe.findAll(listBlock)) {
                val title = em.groupValues[1].trim()
                val url   = em.groupValues[2].trim()
                if (!url.contains("/anime/")) continue
                if (!seen.add(url)) continue
                val tvType = if (title.contains("Movie", ignoreCase = true))
                    TvType.AnimeMovie else TvType.Anime
                results.add(newAnimeSearchResponse(title, url, tvType) {
                    posterUrl = null
                })
            }
        }

        return results
    }

    // ─── getMainPage ──────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val res = app.get(url, headers = mapOf("Accept-Language" to "id-ID,id;q=0.9"))
        val items = parseCards(res.text, res.document).distinctBy { it.url }

        // AnimePlay does not provide valid category pagination.
        // Prevent CloudStream from looping the first page repeatedly.
        return newHomePageResponse(
            request.name,
            items,
            hasNext = false
        )
    }

    // ─── Search (/search?q=...) ───────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q   = URLEncoder.encode(query, "UTF-8")
        val res = app.get("$mainUrl/search?q=$q",
            headers = mapOf("Accept-Language" to "id-ID,id;q=0.9"))
        return parseCards(res.text, res.document).distinctBy { it.url }
    }

    // ─── load (anime detail page) ─────────────────────────────────────────────
    // Evidence from /anime/hourou-musuko :
    //   Title   : <h1 class="text-xl ...">Hourou Musuko</h1>
    //   Poster  : <meta property="og:image" content="https://.../_wp_images/{hash}.webp">
    //   Genres  : <a href="/genre/drama">Drama</a> etc.
    //   Episodes: <a href="/anime/{slug}/episode/{N}">
    //   Desc    : JSON-LD @type=TVSeries "description"
    //   Status  : RSC metaJson "id":"status","value":"Tamat"/"Ongoing"
    //   Type    : RSC metaJson "id":"type","value":"TV"/"Movie"/"OVA"

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = url.substringBefore("?").trimEnd('/')
        val res  = app.get(cleanUrl, headers = mapOf("Accept-Language" to "id-ID,id;q=0.9"))
        val doc  = res.document
        val html = res.text

        // Title
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "Unknown"

        // Poster
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")?.trim()
            ?: toPosterUrl(doc.selectFirst("img[src*='_wp_images']")?.attr("src"))

        // Description — JSON-LD @type=TVSeries
        val description = Regex(
            """"@type"\s*:\s*"TVSeries"[\s\S]*?"description"\s*:\s*"((?:[^"\\]|\\.)*)""""
        ).find(html)?.groupValues?.getOrNull(1)
            ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.trim()
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        // Genres
        val genres = doc.select("a[href*='/genre/']")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        // Year
        val year = Regex(""""datePublished"\s*:\s*"(\d{4})""")
            .find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()

        // Status — RSC metaJson: {"label":"Status","id":"status","value":"Tamat"}
        val metaStatus = Regex(""""id"\s*:\s*"status"[^}]*"value"\s*:\s*"([^"]+)"""")
            .find(html)?.groupValues?.getOrNull(1)?.trim()

        // Type — RSC metaJson: {"label":"Type","id":"type","value":"TV"}
        val metaType = Regex(""""id"\s*:\s*"type"[^}]*"value"\s*:\s*"([^"]+)"""")
            .find(html)?.groupValues?.getOrNull(1)?.trim()

        val showStatus: ShowStatus? = when (metaStatus?.lowercase()) {
            "tamat", "completed", "selesai", "finished" -> ShowStatus.Completed
            "ongoing", "berlangsung", "airing"           -> ShowStatus.Ongoing
            else                                          -> null
        }

        val tvType: TvType = when (metaType?.lowercase()) {
            "movie"                  -> TvType.AnimeMovie
            "ova", "ona", "special"  -> TvType.OVA
            else                     -> {
                if (title.contains("Movie", ignoreCase = true) ||
                    cleanUrl.contains("-movie", ignoreCase = true))
                    TvType.AnimeMovie
                else TvType.Anime
            }
        }

        // Episodes — <a href="/anime/{slug}/episode/{N}">
        val slug = cleanUrl.trimEnd('/').substringAfterLast("/")
        val episodeLinks = doc.select("a[href*='/episode/']")
            .filter { it.attr("href").let { h -> h.contains("/anime/$slug/episode/") || (h.contains("/anime/") && h.contains("/episode/")) } }
            .mapNotNull { a ->
                val href      = a.attr("href").trim()
                val fullEpUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                val epNumStr  = href.trimEnd('/').substringAfterLast("/episode/")
                val epNum     = epNumStr.toIntOrNull()
                    ?: Regex("""(\d+)$""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val epLabel   = cleanEpisodeLabel(a.text(), epNum)
                newEpisode(fullEpUrl) {
                    name    = epLabel
                    episode = epNum
                }
            }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

        // Tracker
        val tracker = APIHolder.getTracker(
            listOf(title), TrackerType.getTypes(tvType), null, true
        )

        // Build response
        return if (tvType == TvType.AnimeMovie) {
            val movieData = episodeLinks.firstOrNull()?.data ?: cleanUrl
            newMovieLoadResponse(title, cleanUrl, TvType.AnimeMovie, movieData) {
                this.posterUrl = tracker?.image ?: poster
                this.plot      = description
                this.tags      = genres
                this.year      = year
            }
        } else {
            newAnimeLoadResponse(title, cleanUrl, tvType) {
                engName             = title
                posterUrl           = tracker?.image ?: poster
                backgroundPosterUrl = tracker?.cover
                addEpisodes(DubStatus.Subbed, episodeLinks)
                plot            = description
                tags            = genres
                this.year       = year
                this.showStatus = showStatus
            }
        }
    }


    private fun cleanEpisodeLabel(rawLabel: String?, epNum: Int?): String {
        val fallback = epNum?.let { "Episode $it" } ?: "Episode"
        val label = rawLabel
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            .orEmpty()

        if (label.isBlank()) return fallback
        if (label.equals("Tonton Sekarang", ignoreCase = true)) return fallback

        val cleaned = epNum?.let {
            label.replace(Regex("""^\s*$it(?=\D)\s*[-.:)]?\s*"""), "").trim()
        } ?: label

        if (cleaned.equals("Tonton Sekarang", ignoreCase = true)) return fallback
        return cleaned.ifBlank { fallback }
    }

    // ─── loadLinks (episode/movie watch page) ─────────────────────────────────
    // Evidence:
    //   Movie still works through the old iframe/wrapper flow.
    //   Series Kanan-sama uses BerkasDrive entries in AnimePlay RSC data:
    //     "streaming":"https://stordl.halahgan.com/streaming//FWI3LC?...1080p.mp4"
    //   StorDL resolves through:
    //     /streaming//{id}?action=stream-url&id={id} -> {"url":"https://stor.halahgan.com/...mp4"}
    //
    // Strategy:
    //   1. Keep the proven iframe flow first, so movie playback is not disturbed.
    //   2. If that emits no link, fetch episode RSC data and read BerkasDrive streamingSources.
    //   3. Resolve stordl/dlgan/dl.berkasdrive wrappers to final MP4/HLS and callback.

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val res = app.get(data, headers = mapOf("Accept-Language" to "id-ID,id;q=0.9"))
        val doc = res.document
        val html = res.text

        val iframeSrcs = doc.select("iframe[src]")
            .map { it.attr("src").trim() }
            .filter { it.isNotBlank() }
            .distinct()

        // Movie/BerkasDrive flow yang sudah jalan tetap prioritas utama.
        if (resolvePlayerCandidates(iframeSrcs, data, subtitleCallback, callback)) {
            return true
        }

        // Series fallback: AnimePlay/Next RSC menyimpan BerkasDrive series di streamingSources.
        val sourceSrcs = (extractBerkasDriveStreamingSources(html) +
            (fetchEpisodeRsc(data)?.let { extractBerkasDriveStreamingSources(it) } ?: emptyList()))
            .distinct()

        return resolvePlayerCandidates(sourceSrcs, data, subtitleCallback, callback)
    }

    // ─── loadLinks helpers ────────────────────────────────────────────────────

    private suspend fun resolvePlayerCandidates(
        rawSources: List<String>,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var found = false

        for (rawSrc in rawSources.distinct()) {
            val src = normalizePlayerUrl(rawSrc) ?: continue

            val trackedCallback: (ExtractorLink) -> Unit = { link ->
                found = true
                callback(link)
            }

            // Stordl series BerkasDrive path from HAR:
            // stordl.halahgan.com/streaming//ID -> action=stream-url -> stor.halahgan.com MP4
            if (resolveStordlStream(src, trackedCallback)) {
                found = true
                continue
            }

            // Keep supported CloudStream extractors alive, but do not trust a bare true
            // unless the extractor actually emits callback links.
            try {
                loadExtractor(src, referer, subtitleCallback, trackedCallback)
            } catch (_: Exception) {
                // Continue with source-backed wrapper handling.
            }
            if (found) continue

            // Decode base64 inner URL and try extractor on it.
            val inner = unwrapBase64(src)
            if (inner != null) {
                try {
                    loadExtractor(inner, src, subtitleCallback, trackedCallback)
                } catch (_: Exception) {
                    // Continue with direct/wrapper handling.
                }
                if (found) continue

                if (isDirectVideo(inner) && emitDirectLink(inner, src, trackedCallback)) {
                    found = true
                    continue
                }
            }

            // Scrape wrapper player page for source/stream_url/mp4/m3u8.
            if (scrapePlayer(src, referer, trackedCallback)) found = true
        }

        return found
    }

    /** Fetch Next/RSC payload for series episode pages when normal HTML has no iframe. */
    private suspend fun fetchEpisodeRsc(data: String): String? {
        val rscUrl = data.substringBefore("#") + if (data.contains("?")) "&_rsc=1" else "?_rsc=1"
        val path = Regex("""https?://[^/]+(/[^?#]*)""").find(data)?.groupValues?.getOrNull(1) ?: "/"
        val detailPath = path.substringBefore("/episode/").ifBlank { path }

        return try {
            app.get(
                rscUrl,
                referer = data,
                headers = mapOf(
                    "Accept" to "*/*",
                    "RSC" to "1",
                    "Next-Url" to detailPath,
                    "Accept-Language" to "id-ID,id;q=0.9",
                ),
            ).text
        } catch (_: Exception) {
            null
        }
    }

    /** Extract only BerkasDrive playback wrappers from AnimePlay HTML/RSC data. */
    private fun extractBerkasDriveStreamingSources(rawHtml: String): List<String> {
        val clean = cleanupEscaped(rawHtml)
        val results = mutableListOf<String>()

        val objectRe = Regex("""\{[^{}]*"streaming"\s*:\s*"([^"]+)"[^{}]*}""")
        val labelRe = Regex(""""label"\s*:\s*"([^"]+)"""")
        for (match in objectRe.findAll(clean)) {
            val block = match.value
            val url = match.groupValues[1].trim()
            val label = labelRe.find(block)?.groupValues?.getOrNull(1).orEmpty()

            val isBerkasDrive = label.contains("Berkasdrive", ignoreCase = true) ||
                url.contains("dl.berkasdrive.com", ignoreCase = true) ||
                url.contains("stordl.halahgan.com/streaming", ignoreCase = true) ||
                url.contains("dlgan.halahgan.com/streaming", ignoreCase = true)

            if (isBerkasDrive && url.isNotBlank()) {
                results.add(url)
            }
        }

        return results.distinct()
    }

    private fun normalizePlayerUrl(src: String?): String? {
        val clean = cleanupEscaped(src ?: return null).trim().takeIf { it.isNotBlank() } ?: return null
        return when {
            clean.startsWith("http", ignoreCase = true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$mainUrl$clean"
            else -> null
        }
    }

    private fun cleanupEscaped(raw: String): String {
        return raw
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")
    }

    private fun isDirectVideo(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4")
    }

    /** Decode base64-wrapped URL from ?url=, ?id=, or ?v= query param */
    private fun unwrapBase64(src: String): String? {
        val param = Regex("""[?&](?:url|id|v)=([A-Za-z0-9+/=]{10,})""")
            .find(src)?.groupValues?.getOrNull(1) ?: return null
        return try {
            val decoded = String(android.util.Base64.decode(param, android.util.Base64.DEFAULT))
            if (decoded.startsWith("http")) decoded else null
        } catch (_: Exception) {
            null
        }
    }

    /** Resolve StorDL BerkasDrive wrapper to direct stor.halahgan.com video URL. */
    private suspend fun resolveStordlStream(
        src: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!src.contains("stordl.halahgan.com/streaming/", ignoreCase = true)) return false

        val id = Regex("""/streaming/+([^/?&#]+)""").find(src)?.groupValues?.getOrNull(1)
            ?: return false

        val apiUrl = "https://stordl.halahgan.com/streaming//$id?action=stream-url&id=$id"
        val json = try {
            app.get(
                apiUrl,
                referer = src,
                headers = mapOf(
                    "Referer" to src,
                    "Origin" to "https://stordl.halahgan.com",
                    "Accept" to "application/json, text/plain, */*",
                ),
            ).text
        } catch (_: Exception) {
            return false
        }

        val direct = Regex(""""(?:url|stream_url)"\s*:\s*"([^"]+)"""")
            .find(cleanupEscaped(json))
            ?.groupValues
            ?.getOrNull(1)
            ?: return false

        return emitDirectLink(direct, "https://stordl.halahgan.com/", callback)
    }

    /** Fetch player wrapper HTML and search for stream_url / JWPlayer / HLS / HTML5 source / MP4. */
    private suspend fun scrapePlayer(
        src: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageHtml = try {
            app.get(
                src,
                referer = referer,
                headers = mapOf("Referer" to referer, "Origin" to mainUrl),
            ).text
        } catch (_: Exception) {
            return false
        }

        val clean = cleanupEscaped(pageHtml)

        // dlgan/stordl wrappers expose both direct_url and stream_url.
        // Prefer stream_url so CloudStream receives playback URL, not download URL.
        val streamUrl = Regex(""""stream_url"\s*:\s*"(https?://[^"]+)"""")
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
        if (!streamUrl.isNullOrBlank() && isDirectVideo(streamUrl)) {
            return emitDirectLink(streamUrl, src, callback)
        }

        val directFieldUrl = Regex(""""(?:direct_url|file|url)"\s*:\s*"(https?://[^"]+)"""")
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
        if (!directFieldUrl.isNullOrBlank() && isDirectVideo(directFieldUrl)) {
            return emitDirectLink(directFieldUrl, src, callback)
        }

        // HLS m3u8
        val hlsUrl = Regex(""""(https?://[^"]+\.m3u8[^"]*)"""").find(clean)
            ?.groupValues?.getOrNull(1)
        if (!hlsUrl.isNullOrBlank()) {
            return emitDirectLink(hlsUrl, src, callback)
        }

        // HTML5 <source src="...">
        val sourceEl = org.jsoup.Jsoup.parse(clean).selectFirst("source[src]")?.attr("src")
        if (!sourceEl.isNullOrBlank()) {
            val videoUrl = if (sourceEl.startsWith("//")) "https:$sourceEl" else sourceEl
            return emitDirectLink(videoUrl, src, callback)
        }

        // MP4 anywhere
        val mp4Url = Regex(""""(https?://[^"]+\.mp4[^"]*)"""").find(clean)
            ?.groupValues?.getOrNull(1)
        if (!mp4Url.isNullOrBlank()) {
            return emitDirectLink(mp4Url, src, callback)
        }

        return false
    }

    private suspend fun emitDirectLink(
        rawUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val videoUrl = cleanupEscaped(rawUrl).trim()
        if (!isDirectVideo(videoUrl)) return false

        callback(
            newExtractorLink(
                source = name,
                name = if (videoUrl.contains(".m3u8", ignoreCase = true)) "HLS" else "BerkasDrive",
                url = videoUrl,
                type = if (videoUrl.contains(".m3u8", ignoreCase = true))
                    ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
