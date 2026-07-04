package com.sad25kag.dramaid

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

class DramaIdProvider : MainAPI() {
    override var mainUrl = "https://drama-id.com"
    override var name = "DramaID"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbApiKey = "b030404650f279792a8d3287232358e3"
    private val tmdbImage = "https://image.tmdb.org/t/p"
    private val detailPathPattern = Regex("""(?i)/nonton-[^/?#]+/?(?:$|[?#])""")

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Update Terbaru",

        "$mainUrl/negara/korea-selatan/page/%d/" to "Drama Korea",
        "$mainUrl/negara/china/page/%d/" to "Drama China",
        "$mainUrl/negara/japan/page/%d/" to "Drama Jepang",
        "$mainUrl/negara/thailand/page/%d/" to "Drama Thailand",
        "$mainUrl/negara/taiwan/page/%d/" to "Drama Taiwan",
        "$mainUrl/negara/hongkong/page/%d/" to "Drama Hongkong",
        "$mainUrl/negara/philippines/page/%d/" to "Drama Philippines",

        "$mainUrl/status-drama/ongoing/page/%d/" to "Ongoing",
        "$mainUrl/status-drama/complete/page/%d/" to "Tamat",

        "$mainUrl/genre/action/page/%d/" to "Action",
        "$mainUrl/genre/adventure/page/%d/" to "Adventure",
        "$mainUrl/genre/business/page/%d/" to "Business",
        "$mainUrl/genre/comedy/page/%d/" to "Comedy",
        "$mainUrl/genre/crime/page/%d/" to "Crime",
        "$mainUrl/genre/drama/page/%d/" to "Drama",
        "$mainUrl/genre/family/page/%d/" to "Family",
        "$mainUrl/genre/fantasy/page/%d/" to "Fantasy",
        "$mainUrl/genre/food/page/%d/" to "Food",
        "$mainUrl/genre/friendship/page/%d/" to "Friendship",
        "$mainUrl/genre/historical/page/%d/" to "Historical",
        "$mainUrl/genre/horror/page/%d/" to "Horror",
        "$mainUrl/genre/law/page/%d/" to "Law",
        "$mainUrl/genre/life/page/%d/" to "Life",
        "$mainUrl/genre/melodrama/page/%d/" to "Melodrama",
        "$mainUrl/genre/military/page/%d/" to "Military",
        "$mainUrl/genre/music/page/%d/" to "Music",
        "$mainUrl/genre/mystery/page/%d/" to "Mystery",
        "$mainUrl/genre/office/page/%d/" to "Office",
        "$mainUrl/genre/political/page/%d/" to "Political",
        "$mainUrl/genre/psychological/page/%d/" to "Psychological",
        "$mainUrl/genre/romance/page/%d/" to "Romance",
        "$mainUrl/genre/school/page/%d/" to "School",
        "$mainUrl/genre/sci-fi/page/%d/" to "Sci-Fi",
        "$mainUrl/genre/sports/page/%d/" to "Sports",
        "$mainUrl/genre/supernatural/page/%d/" to "Supernatural",
        "$mainUrl/genre/thriller/page/%d/" to "Thriller",
        "$mainUrl/genre/variety-show/page/%d/" to "Variety Show",
        "$mainUrl/genre/war/page/%d/" to "War",
        "$mainUrl/genre/youth/page/%d/" to "Youth",

        "$mainUrl/rating/semua-umur/page/%d/" to "Semua Umur",
        "$mainUrl/rating/13/page/%d/" to "Rating 13",
        "$mainUrl/rating/15/page/%d/" to "Rating 15",
        "$mainUrl/rating/17/page/%d/" to "Rating 17",
        "$mainUrl/rating/18/page/%d/" to "Rating 18"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val document = app.get(url, referer = "$mainUrl/").document
        val items = document.toSearchResults()
        val hasNext = document.select(
            ".pagination a[href]:matchesOwn((?i)Next), " +
                ".pagination a[href*='/page/${page + 1}/'], " +
                "a.next[href], a[href*='/page/${page + 1}/']"
        ).isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", referer = "$mainUrl/").document
        return document.toSearchResults()
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = normalizeUrl(url, mainUrl) ?: throw ErrorLoadingException("Invalid URL")
        if (!fixedUrl.isDramaIdDetailUrl()) throw ErrorLoadingException("Invalid DramaID detail URL")

        val document = app.get(fixedUrl, referer = "$mainUrl/").document
        val listingCard = document.findListingCard(fixedUrl)

        val title = listOf(
            detailValue(document, "Judul"),
            listingCard?.listingTitle(fixedUrl),
            titleFromSlug(fixedUrl),
            document.selectFirst("h1.single-title, h1.single_h2, h1.entry-title, h1")?.text()?.takeUnless { it.isDramaIdIndexTitle() },
            document.selectFirst("meta[property=og:title]")?.attr("content")?.takeUnless { it.isDramaIdIndexTitle() },
            document.selectFirst("title")?.text()?.takeUnless { it.isDramaIdIndexTitle() }
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Title not found")

        val listingPoster = listingCard?.selectFirst(".thumbnail img, img, .poster img")
            ?.imageUrl()
            ?.takeIf { !it.isDramaIdSiteAsset() }

        val sourcePoster = listingPoster ?: document.select(
            ".thumbnail_single img, " +
                ".poster img, " +
                "img.wp-post-image, " +
                ".entry-content img, " +
                "meta[property=og:image]"
        )
            .mapNotNull { it.imageUrl() }
            .firstOrNull { !it.isDramaIdSiteAsset() }

        val plot = document.select("#sinopsis p, .synopsis p, .entry-content p")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.isDramaIdInstructionText() }
            .joinToString("\n")
            .trim()
            .ifBlank { null }

        val year = detailValue(document, "Tahun")?.let(::extractYear)
        val rating = detailValue(document, "Skor")?.toScore()
        val tags = document.select("#informasi li:has(strong:matchesOwn((?i)Genres)) a, .info li:has(strong:matchesOwn((?i)Genres)) a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val status = getStatus(detailValue(document, "Status"))
        val duration = detailValue(document, "Durasi")?.durationToMinutes()
        val typeText = detailValue(document, "Tipe").orEmpty()

        val tmdb = fetchTmdbMetadata(title, year, typeText)
        val finalTitle = tmdb?.title?.takeIf { it.isNotBlank() } ?: title
        val finalPoster = tmdb?.poster ?: sourcePoster
        val finalBackdrop = tmdb?.backdrop ?: finalPoster
        val finalPlot = tmdb?.plot?.takeIf { it.isNotBlank() } ?: plot
        val finalTags = tmdb?.tags?.takeIf { it.isNotEmpty() } ?: tags

        val episodes = document.select(
            ".daftar-episode li a[href*='episode='], " +
                ".episode-list li a[href*='episode='], " +
                "a[href*='episode=']"
        )
            .mapNotNull { it.toEpisode() }
            .ifEmpty { listingCard?.toListingEpisodes(fixedUrl).orEmpty() }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

        val recommendations = document.select("article")
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == fixedUrl }
            .distinctBy { it.url }

        val isMovie = typeText.contains("movie", true) && episodes.size <= 1

        return if (isMovie) {
            newMovieLoadResponse(finalTitle, fixedUrl, TvType.Movie, episodes.firstOrNull()?.data ?: fixedUrl) {
                posterUrl = finalPoster
                backgroundPosterUrl = finalBackdrop
                this.year = tmdb?.year ?: year
                finalPlot?.let { this.plot = it }
                this.tags = finalTags
                (tmdb?.rating ?: rating)?.let { score = Score.from10(it) }
                (tmdb?.duration ?: duration)?.let { this.duration = it }
                tmdb?.actors?.takeIf { it.isNotEmpty() }?.let { actors = it }
                tmdb?.id?.let { addTMDbId(it.toString()) }
                this.recommendations = recommendations
            }
        } else {
            val safeEpisodes = episodes.ifEmpty { listOf(newEpisode(fixedUrl) { name = "Episode 1"; episode = 1 }) }
            newTvSeriesLoadResponse(finalTitle, fixedUrl, TvType.AsianDrama, safeEpisodes) {
                posterUrl = finalPoster
                backgroundPosterUrl = finalBackdrop
                this.year = tmdb?.year ?: year
                finalPlot?.let { this.plot = it }
                this.tags = finalTags
                (tmdb?.rating ?: rating)?.let { score = Score.from10(it) }
                showStatus = tmdb?.status ?: status
                (tmdb?.duration ?: duration)?.let { this.duration = it }
                tmdb?.actors?.takeIf { it.isNotEmpty() }?.let { actors = it }
                tmdb?.id?.let { addTMDbId(it.toString()) }
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixedUrl = normalizeUrl(data, mainUrl) ?: return false
        val page = runCatching {
            app.get(
                fixedUrl,
                referer = "$mainUrl/",
                headers = mapOf("User-Agent" to USER_AGENT)
            ).text
        }.getOrNull() ?: return false

        val document = Jsoup.parse(page, fixedUrl)
        val emitted = linkedSetOf<String>()
        val queue = ArrayDeque<Pair<String, String>>()
        val visited = linkedSetOf<String>()
        var delivered = 0

        suspend fun emitDirectIfMedia(url: String, label: String, refererUrl: String, qualityLabel: String? = null): Boolean {
            if (!url.isMediaUrl() || url.isBlockedMediaOrAd() || !emitted.add(url)) return false
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name ${label.cleanLabel()}",
                    url = url,
                    type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    referer = refererUrl
                    quality = qualityFromLabel(qualityLabel ?: label)
                    headers = mapOf(
                        "Referer" to refererUrl,
                        "Range" to "bytes=0-",
                        "User-Agent" to USER_AGENT,
                    )
                }
            )
            delivered++
            return true
        }

        fun addCandidate(raw: String?, refererUrl: String = fixedUrl) {
            val normalized = normalizeUrl(raw.orEmpty(), refererUrl) ?: return
            if (normalized.isBlockedMediaOrAd()) return
            if (normalized.isMediaUrl() || normalized.isKnownResolverUrl()) {
                queue.add(normalized to refererUrl)
            }
        }

        fun decodeAndCollect(value: String?, refererUrl: String = fixedUrl) {
            val raw = value.orEmpty().trim()
            if (raw.isBlank()) return
            addCandidate(raw, refererUrl)

            decodeBase64(raw)?.let { decoded ->
                collectCandidatesFromText(decoded, refererUrl).forEach { addCandidate(it, refererUrl) }
                val decodedDoc = Jsoup.parse(decoded, refererUrl)
                decodedDoc.select("iframe[src], iframe[data-src], source[src], video[src], a[href], [data-url], [data-src], [data-video], [data-link]")
                    .forEach { element ->
                        addCandidate(element.attr("src"), refererUrl)
                        addCandidate(element.attr("data-src"), refererUrl)
                        addCandidate(element.attr("data-url"), refererUrl)
                        addCandidate(element.attr("data-video"), refererUrl)
                        addCandidate(element.attr("data-link"), refererUrl)
                        addCandidate(element.attr("href"), refererUrl)
                    }
            }
        }

        suspend fun collectFromDocument(doc: Document, html: String, refererUrl: String) {
            doc.select(
                ".streaming_load[data], " +
                    ".resolusi-list li[data], " +
                    ".server-list li[data], " +
                    ".mobius option[value], " +
                    "[data], [data-url], [data-src], [data-video], [data-link], " +
                    "iframe[src], iframe[data-src], iframe[data-litespeed-src], " +
                    "source[src], video[src], a[href]"
            ).forEach { element ->
                decodeAndCollect(element.attr("data"), refererUrl)
                decodeAndCollect(element.attr("value"), refererUrl)
                addCandidate(element.attr("data-url"), refererUrl)
                addCandidate(element.attr("data-src"), refererUrl)
                addCandidate(element.attr("data-video"), refererUrl)
                addCandidate(element.attr("data-link"), refererUrl)
                addCandidate(element.attr("data-litespeed-src"), refererUrl)
                addCandidate(element.attr("src"), refererUrl)
                addCandidate(element.attr("href"), refererUrl)
            }

            collectCandidatesFromText(html, refererUrl).forEach { addCandidate(it, refererUrl) }

            for (element in doc.select(".resolusi-list li[data], .server-list li[data], .streaming_load[data]")) {
                val decodedJson = decodeBase64(element.attr("data")) ?: continue
                val resolution = parseResolutionData(decodedJson)
                val servers = resolution?.links.orEmpty().ifEmpty {
                    listOfNotNull(parseServerData(decodedJson))
                }
                val qualityLabel = resolution?.resolution ?: element.text().trim()

                resolution?.subtitle_url
                    ?.takeIf { it.isNotBlank() }
                    ?.let { normalizeUrl(it, refererUrl) }
                    ?.let { subtitleCallback(newSubtitleFile("Indonesian", it)) }

                for (server in servers) {
                    val serverUrl = normalizeUrl(server.url.orEmpty(), refererUrl) ?: continue
                    if (serverUrl.isMediaUrl()) {
                        emitDirectIfMedia(
                            serverUrl,
                            qualityLabel.ifBlank { server.urutan_text ?: "Server" },
                            refererUrl,
                            qualityLabel
                        )
                    } else {
                        addCandidate(serverUrl, refererUrl)
                    }
                }
            }
        }

        collectFromDocument(document, page, fixedUrl)

        var safety = 0
        while (queue.isNotEmpty() && safety++ < 90) {
            val (target, refererUrl) = queue.removeFirst()
            if (!visited.add(target)) continue
            if (target.isBlockedMediaOrAd()) continue

            if (target.isMediaUrl() && !target.isResolverUrl()) {
                if (
                    emitDirectIfMedia(
                        target,
                        target.substringAfterLast("/").substringBefore("?").ifBlank { "Server" },
                        refererUrl,
                        target
                    )
                ) continue
            }

            decodeBerkasDriveId(target)?.let { addCandidate(it, refererUrl) }

            val extractorSuccess = runCatching {
                loadExtractor(target, refererUrl, subtitleCallback) { link ->
                    val linkUrl = link.url
                    if (linkUrl.isNotBlank() && !linkUrl.isBlockedMediaOrAd() && emitted.add(linkUrl)) {
                        delivered++
                        callback(link)
                    }
                }
            }.getOrDefault(false)

            if (extractorSuccess && delivered > 0) continue

            if (target.shouldCrawlResolver()) {
                val nested = runCatching {
                    app.get(
                        target,
                        referer = refererUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                        )
                    ).text
                }.getOrNull() ?: continue

                collectFromDocument(Jsoup.parse(nested, target), nested, target)
            }
        }

        return delivered > 0
    }

    private fun Document.toSearchResults(): List<SearchResponse> {
        return select("article, div.bs, div.listupd article, .post, .item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = select("a[href*='/nonton-']")
            .firstOrNull()
            ?: selectFirst("h3.title_post a[href], .thumbnail a[href], a[href]")
            ?: return null

        val href = normalizeUrl(link.attr("href"), mainUrl) ?: return null
        if (!href.contains("/nonton-", true)) return null

        val title = listOf(
            link.attr("title"),
            selectFirst("h3.title_post a, h2 a, h3 a, .title a, .tt, .entry-title a")?.text(),
            selectFirst("img[alt]")?.attr("alt"),
            link.text(),
            titleFromSlug(href),
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = selectFirst(".thumbnail img, img, .poster img")?.imageUrl()?.takeIf { !it.isDramaIdSiteAsset() }
        val type = if (text().contains("Episode:", true) || text().contains("Episode", true)) TvType.AsianDrama else TvType.Movie

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, type) {
                posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, href, type) {
                posterUrl = poster
            }
        }
    }

    private fun Element.toEpisode(): Episode? {
        val href = normalizeUrl(attr("href"), mainUrl) ?: return null
        if (!href.contains("episode=", true) && !href.contains("/nonton-", true)) return null

        val title = attr("title")
            .ifBlank { selectFirst(".title_episode, .title_episode_2")?.text().orEmpty() }
            .ifBlank { text() }
            .replace(Regex("""\s+"""), " ")
            .trim()

        val episodeNumber = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(title.ifBlank { href })
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""[?&]episode=(\d+)""", RegexOption.IGNORE_CASE)
                .find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        return newEpisode(href) {
            name = episodeNumber?.let { "Episode $it" } ?: title.ifBlank { "Episode" }
            episode = episodeNumber
        }
    }

    private fun Document.findListingCard(detailUrl: String): Element? {
        val targetPath = normalizeUrl(detailUrl, mainUrl)
            ?.substringBefore("?")
            ?.removeSuffix("/")
            ?: return null

        return select("article, div.bs, div.listupd article, .post, .item")
            .firstOrNull { card ->
                card.select("a[href*='/nonton-']").any { link ->
                    normalizeUrl(link.attr("href"), mainUrl)
                        ?.substringBefore("?")
                        ?.removeSuffix("/")
                        ?.equals(targetPath, true) == true
                }
            }
    }

    private fun Element.listingTitle(detailUrl: String): String? {
        return listOf(
            selectFirst("h3.title_post a, h2 a, h3 a, .title a, .tt, .entry-title a, a[href*='/nonton-']")?.text(),
            selectFirst("a[href*='/nonton-']")?.attr("title"),
            selectFirst("img[alt]")?.attr("alt"),
            titleFromSlug(detailUrl)
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() && !it.isDramaIdIndexTitle() }
    }

    private fun Element.toListingEpisodes(detailUrl: String): List<Episode> {
        val info = text().replace(Regex("""\s+"""), " ")
        val range = Regex("""Episode:\s*(\d+)\s*-\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(info)
        val single = Regex("""Episode:\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(info)

        val firstEpisode = range?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: single?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return emptyList()
        val lastEpisode = range?.groupValues?.getOrNull(2)?.toIntOrNull() ?: firstEpisode
        val start = firstEpisode.coerceAtLeast(1)
        val end = lastEpisode.coerceAtLeast(start)
        val cleanUrl = detailUrl.substringBefore("?").removeSuffix("/")

        return (start..end).map { episodeNumber ->
            newEpisode("$cleanUrl/?episode=$episodeNumber") {
                name = "Episode $episodeNumber"
                episode = episodeNumber
            }
        }
    }

    private suspend fun fetchTmdbMetadata(title: String, year: Int?, typeText: String): TmdbMetadata? {
        val query = title.toTmdbQuery()
        if (query.isBlank()) return null

        val preferredType = if (typeText.contains("movie", true)) "movie" else "tv"
        return fetchTmdbMetadataByType(query, year, preferredType)
            ?: fetchTmdbMetadataByType(query, year, if (preferredType == "tv") "movie" else "tv")
    }

    private suspend fun fetchTmdbMetadataByType(query: String, year: Int?, type: String): TmdbMetadata? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$tmdbApi/search/$type?api_key=$tmdbApiKey&language=en-US&query=$encoded&page=1&include_adult=false"
        val search = runCatching { app.get(searchUrl).parsedSafe<TmdbSearchResponse>() }.getOrNull()
        val selected = search?.results
            .orEmpty()
            .filter { it.id != null && !it.bestTitle.isNullOrBlank() }
            .maxByOrNull { it.matchScore(query, year) }
            ?: return null

        val append = "credits,keywords"
        val detailUrl = "$tmdbApi/$type/${selected.id}?api_key=$tmdbApiKey&language=en-US&append_to_response=$append"
        val detail = runCatching { app.get(detailUrl).parsedSafe<TmdbDetail>() }.getOrNull() ?: return null

        val tags = detail.keywords?.results.orEmpty()
            .ifEmpty { detail.keywords?.keywords.orEmpty() }
            .mapNotNull { it.name?.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty {
                detail.genres.orEmpty()
                    .mapNotNull { it.name?.trim() }
                    .filter { it.isNotBlank() }
            }

        val actors = detail.credits?.cast
            .orEmpty()
            .take(20)
            .mapNotNull { cast ->
                val actorName = cast.name ?: cast.originalName ?: return@mapNotNull null
                ActorData(
                    Actor(actorName, tmdbPoster(cast.profilePath)),
                    roleString = cast.character
                )
            }

        val releaseYear = (detail.releaseDate ?: detail.firstAirDate)
            ?.split("-")
            ?.firstOrNull()
            ?.toIntOrNull()

        return TmdbMetadata(
            id = detail.id ?: selected.id ?: return null,
            title = detail.title ?: detail.name ?: selected.bestTitle,
            year = releaseYear,
            poster = tmdbPoster(detail.posterPath, original = true),
            backdrop = tmdbPoster(detail.backdropPath, original = true),
            plot = detail.overview?.trim()?.ifBlank { null },
            tags = tags,
            rating = detail.voteAverage,
            status = detail.status?.let(::getStatus),
            duration = detail.runtime ?: detail.episodeRunTime?.firstOrNull(),
            actors = actors
        )
    }

    private val TmdbSearchItem.bestTitle: String?
        get() = title ?: name ?: originalTitle ?: originalName

    private fun TmdbSearchItem.matchScore(query: String, year: Int?): Int {
        val itemTitle = bestTitle.orEmpty()
        val normalizedQuery = query.normalizeTmdbName()
        val normalizedItem = itemTitle.normalizeTmdbName()
        val itemYear = (releaseDate ?: firstAirDate)?.split("-")?.firstOrNull()?.toIntOrNull()
        var score = voteCount ?: 0
        if (normalizedItem == normalizedQuery) score += 10_000
        if (normalizedItem.contains(normalizedQuery) || normalizedQuery.contains(normalizedItem)) score += 2_500
        if (year != null && itemYear == year) score += 5_000
        return score
    }

    private fun String.toTmdbQuery(): String {
        return cleanTitle()
            .replace(Regex("""(?i)\s*\((?:19|20)\d{2}\)\s*"""), " ")
            .replace(Regex("""(?i)\s+season\s+\d+\s*$"""), " ")
            .replace(Regex("""(?i)\s+episode\s+\d+.*$"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.normalizeTmdbName(): String {
        return lowercase()
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun tmdbPoster(path: String?, original: Boolean = false): String? {
        val clean = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) return clean
        val size = if (original) "original" else "w500"
        return if (clean.startsWith("/")) "$tmdbImage/$size$clean" else "$tmdbImage/$size/$clean"
    }

    private fun String.isDramaIdDetailUrl(): Boolean {
        return startsWith(mainUrl, true) && detailPathPattern.containsMatchIn(this)
    }

    private fun String.isDramaIdSiteAsset(): Boolean {
        val value = lowercase()
        return value.contains("favicon") ||
            value.contains("t2.gstatic.com/favicon") ||
            value.contains("/logo") ||
            value.contains("logo.") ||
            value.endsWith(".svg")
    }

    private fun String.isDramaIdIndexTitle(): Boolean {
        val value = cleanTitle().lowercase()
        return value == "dramaid" ||
            value.contains("cari drama di dramaid") ||
            value.contains("nonton dan download drama korea")
    }

    private fun detailValue(document: Document, label: String): String? {
        return document.select("#informasi li, .info li, .spe span, .info-content span")
            .firstOrNull { item ->
                item.selectFirst("strong, b")?.text()
                    ?.replace(":", "")
                    ?.trim()
                    ?.equals(label, true) == true ||
                    item.text().substringBefore(":").trim().equals(label, true)
            }
            ?.let { item ->
                val clone = item.clone()
                clone.select("strong, b").remove()
                clone.text().substringAfter(":", clone.text()).trim().ifBlank { null }
            }
    }

    private fun Element.imageUrl(): String? {
        return listOf(
            attr("abs:content"),
            attr("abs:data-src"),
            attr("abs:data-lazy-src"),
            attr("abs:srcset").substringBefore(" "),
            attr("abs:src"),
            attr("content"),
            attr("data-src"),
            attr("data-lazy-src"),
            attr("srcset").substringBefore(" "),
            attr("src"),
        ).firstOrNull { it.isNotBlank() }?.let { normalizeUrl(it, mainUrl) ?: fixUrl(it) }
    }

    private fun pageUrl(pattern: String, page: Int): String {
        if (page > 1) return pattern.format(page)
        return pattern
            .replace("/page/%d/", "/")
            .replace("page/%d/", "")
            .replace("%d", "1")
    }

    private fun normalizeUrl(raw: String, baseUrl: String): String? {
        val clean = raw.trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .takeIf { it.isNotBlank() && !it.startsWith("javascript:", true) && !it.startsWith("data:", true) }
            ?: return null

        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrNull()
        }
    }

    private fun titleFromSlug(url: String): String? {
        return normalizeUrl(url, mainUrl)
            ?.substringBefore("?")
            ?.substringAfterLast("/")
            ?.takeIf { it.isNotBlank() }
            ?.removePrefix("nonton-")
            ?.replace("-", " ")
            ?.replace(Regex("""\b\w""")) { it.value.uppercase() }
    }

    private fun collectCandidatesFromText(text: String, baseUrl: String): List<String> {
        val output = linkedSetOf<String>()
        val clean = text
            .jsonUrlDecode()
            .replace("&amp;", "&")

        Regex("""https?://[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.value }
            .filter { it.isKnownResolverUrl() || it.isMediaUrl() }
            .forEach { output.add(it) }

        Regex("""https?%3A%2F%2F[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value) }
            .filter { it.isKnownResolverUrl() || it.isMediaUrl() }
            .forEach { output.add(it) }

        Regex(
            """(?:file|src|url|source|video|videoUrl|streamUrl|stream_url|downloadUrl|download_url|data-url|data-src|hls|hlsUrl|hls_url)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
            .findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .mapNotNull { normalizeUrl(it, baseUrl) }
            .filter { it.isKnownResolverUrl() || it.isMediaUrl() }
            .forEach { output.add(it) }

        return output.toList()
    }

    private fun parseResolutionData(json: String): ResolutionData? {
        return runCatching {
            val obj = JSONObject(json)
            val links = mutableListOf<ServerData>()
            val array = obj.optJSONArray("links")
            if (array != null) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    links.add(item.toServerData())
                }
            }

            ResolutionData(
                resolution = obj.optStringOrNull("resolution"),
                subtitle_url = obj.optStringOrNull("subtitle_url"),
                links = links.takeIf { it.isNotEmpty() },
            )
        }.getOrNull()
    }

    private fun parseServerData(json: String): ServerData? {
        return runCatching {
            JSONObject(json).toServerData().takeIf { !it.url.isNullOrBlank() }
        }.getOrNull()
    }

    private fun JSONObject.toServerData(): ServerData {
        return ServerData(
            url = optStringOrNull("url"),
            mode = optStringOrNull("mode"),
            urutan_text = optStringOrNull("urutan_text"),
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        return optString(key).trim().ifBlank { null }
    }

    private fun decodeBerkasDriveId(url: String): String? {
        if (!url.contains("dl.berkasdrive.com/streaming", true)) return null
        return url.substringAfter("?", "")
            .split("&")
            .firstOrNull { it.substringBefore("=") == "id" }
            ?.substringAfter("=")
            ?.let { URLDecoder.decode(it, "UTF-8") }
            ?.let(::decodeBase64)
            ?.let { normalizeUrl(it, mainUrl) }
    }

    private fun decodeBase64(value: String): String? {
        val clean = value.trim().replace("\\s".toRegex(), "")
        if (clean.isBlank()) return null
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return runCatching { String(Base64.getDecoder().decode(padded)) }.getOrNull()
    }

    private fun getStatus(value: String?): ShowStatus? {
        return when {
            value == null -> null
            value.contains("ongoing", true) -> ShowStatus.Ongoing
            value.contains("returning", true) -> ShowStatus.Ongoing
            value.contains("complete", true) || value.contains("tamat", true) || value.contains("ended", true) -> ShowStatus.Completed
            else -> null
        }
    }

    private fun extractYear(value: String): Int? {
        return Regex("""(19|20)\d{2}""").find(value)?.value?.toIntOrNull()
    }

    private fun String.toScore(): Double? {
        return Regex("""\d+(?:\.\d+)?""").find(this)?.value?.toDoubleOrNull()
    }

    private fun String.durationToMinutes(): Int? {
        val hours = Regex("""(\d+)\s*hr""", RegexOption.IGNORE_CASE).find(this)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val minutes = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE).find(this)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return (hours * 60 + minutes).takeIf { it > 0 }
    }

    private fun qualityFromLabel(value: String): Int {
        return Regex("""\b(2160|1440|1080|720|480|360|240)p?\b""", RegexOption.IGNORE_CASE)
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun String.cleanTitle(): String {
        return Jsoup.parse(this).text()
            .replace(Regex("""(?i)^Nonton\s+(?:Drakor|Drama)\s+"""), "")
            .replace(Regex("""(?i)\s+Subtitle\s+Indonesia.*$"""), "")
            .replace(Regex("""(?i)\s+Sub\s+Indo.*$"""), "")
            .replace(Regex("""(?i)\s+Episode\s+\d+.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.isDramaIdInstructionText(): Boolean {
        return contains("Pilih Server", true) ||
            contains("WARP sebagai VPN", true) ||
            contains("Playstore", true) ||
            contains("App Store", true) ||
            contains("silahkan download", true)
    }

    private fun String.cleanLabel(): String {
        return replace(Regex("""\s+"""), " ").trim().ifBlank { "Server" }
    }

    private fun String.jsonUrlDecode(): String {
        return replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003D", "=")
            .replace("\\u003f", "?")
            .replace("\\u003F", "?")
            .replace("\\u002F", "/")
    }

    private fun String.isMediaUrl(): Boolean {
        return Regex("""(?i)\.(mp4|m3u8)(?:$|[?#&])""").containsMatchIn(this)
    }

    private fun String.isResolverUrl(): Boolean {
        return contains("stordl.halahgan.com", true) || contains("dl.berkasdrive.com/streaming", true)
    }

    private fun String.isKnownResolverUrl(): Boolean {
        val value = lowercase()
        return value.contains("stordl.halahgan.com") ||
            value.contains("dl.berkasdrive.com") ||
            value.contains("berkasdrive.com") ||
            value.contains("halahgan.com") ||
            value.contains("streaming") ||
            value.contains("/embed/") ||
            value.contains("/player/") ||
            value.contains("filemoon") ||
            value.contains("streamwish") ||
            value.contains("wishfast") ||
            value.contains("dood") ||
            value.contains("streamtape") ||
            value.contains("vidhide") ||
            value.contains("vidguard") ||
            value.contains("voe.") ||
            value.contains("mixdrop") ||
            value.contains("mp4upload") ||
            value.contains("lulustream") ||
            value.contains("lulu") ||
            value.contains("krakenfiles") ||
            value.contains("acefile") ||
            value.contains("drive.google") ||
            value.contains("ok.ru")
    }

    private fun String.shouldCrawlResolver(): Boolean {
        val value = lowercase()
        return value.contains("stordl.halahgan.com") ||
            value.contains("dl.berkasdrive.com") ||
            value.contains("berkasdrive.com") ||
            value.contains("halahgan.com") ||
            value.contains("/embed/") ||
            value.contains("/player/") ||
            value.contains("streaming")
    }

    private fun String.isBlockedMediaOrAd(): Boolean {
        val value = lowercase()
        return value.isBlank() ||
            value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("adsbygoogle") ||
            value.contains("googletagmanager") ||
            value.contains("google-analytics") ||
            value.contains("histats") ||
            value.contains("facebook.com") ||
            value.contains("twitter.com") ||
            value.contains("whatsapp") ||
            value.contains("mailto:") ||
            value.endsWith(".js") ||
            value.endsWith(".css") ||
            value.endsWith(".jpg") ||
            value.endsWith(".jpeg") ||
            value.endsWith(".png") ||
            value.endsWith(".webp") ||
            value.endsWith(".gif") ||
            value.endsWith(".svg")
    }

    data class ResolutionData(
        val resolution: String? = null,
        val subtitle_url: String? = null,
        val links: List<ServerData>? = null,
    )

    data class ServerData(
        val url: String? = null,
        val mode: String? = null,
        val urutan_text: String? = null,
    )

    data class TmdbMetadata(
        val id: Int,
        val title: String? = null,
        val year: Int? = null,
        val poster: String? = null,
        val backdrop: String? = null,
        val plot: String? = null,
        val tags: List<String> = emptyList(),
        val rating: Double? = null,
        val status: ShowStatus? = null,
        val duration: Int? = null,
        val actors: List<ActorData> = emptyList(),
    )

    data class TmdbSearchResponse(
        @param:JsonProperty("results")
        val results: List<TmdbSearchItem>? = null,
    )

    data class TmdbSearchItem(
        @param:JsonProperty("id")
        val id: Int? = null,
        @param:JsonProperty("name")
        val name: String? = null,
        @param:JsonProperty("title")
        val title: String? = null,
        @param:JsonProperty("original_name")
        val originalName: String? = null,
        @param:JsonProperty("original_title")
        val originalTitle: String? = null,
        @param:JsonProperty("first_air_date")
        val firstAirDate: String? = null,
        @param:JsonProperty("release_date")
        val releaseDate: String? = null,
        @param:JsonProperty("vote_count")
        val voteCount: Int? = null,
    )

    data class TmdbDetail(
        @param:JsonProperty("id")
        val id: Int? = null,
        @param:JsonProperty("name")
        val name: String? = null,
        @param:JsonProperty("title")
        val title: String? = null,
        @param:JsonProperty("overview")
        val overview: String? = null,
        @param:JsonProperty("poster_path")
        val posterPath: String? = null,
        @param:JsonProperty("backdrop_path")
        val backdropPath: String? = null,
        @param:JsonProperty("first_air_date")
        val firstAirDate: String? = null,
        @param:JsonProperty("release_date")
        val releaseDate: String? = null,
        @param:JsonProperty("vote_average")
        val voteAverage: Double? = null,
        @param:JsonProperty("episode_run_time")
        val episodeRunTime: List<Int>? = null,
        @param:JsonProperty("runtime")
        val runtime: Int? = null,
        @param:JsonProperty("status")
        val status: String? = null,
        @param:JsonProperty("genres")
        val genres: List<TmdbNamed>? = null,
        @param:JsonProperty("keywords")
        val keywords: TmdbKeywords? = null,
        @param:JsonProperty("credits")
        val credits: TmdbCredits? = null,
    )

    data class TmdbNamed(
        @param:JsonProperty("name")
        val name: String? = null,
    )

    data class TmdbKeywords(
        @param:JsonProperty("results")
        val results: List<TmdbNamed>? = null,
        @param:JsonProperty("keywords")
        val keywords: List<TmdbNamed>? = null,
    )

    data class TmdbCredits(
        @param:JsonProperty("cast")
        val cast: List<TmdbCast>? = null,
    )

    data class TmdbCast(
        @param:JsonProperty("name")
        val name: String? = null,
        @param:JsonProperty("original_name")
        val originalName: String? = null,
        @param:JsonProperty("character")
        val character: String? = null,
        @param:JsonProperty("profile_path")
        val profilePath: String? = null,
    )
}
