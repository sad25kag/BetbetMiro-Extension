package com.sad25kag.betbetlivetv

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class BetbetLiveTvProvider : MainAPI() {
    override var mainUrl = "https://globetv.app"
    override var name = "Betbet Live TV"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "id" to "🇮🇩 Indonesia",
        "ph" to "🇵🇭 Philippines",
        "th" to "🇹🇭 Thailand",
        "jp" to "🇯🇵 Japan",
        "kr" to "🇰🇷 South Korea",
        "in" to "🇮🇳 India",
        "us" to "🇺🇸 United States"
    )

    private val cache = ConcurrentHashMap<String, List<LiveChannel>>()
    private val logoCache = ConcurrentHashMap<String, String>()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val country = countryByCode(request.data) ?: return newHomePageResponse(
            HomePageList(request.name, emptyList(), isHorizontalImages = true),
            hasNext = false
        )

        val channels = runCatching { channelsFor(country) }
            .onFailure { logError(it) }
            .getOrDefault(emptyList())

        val start = ((page.coerceAtLeast(1) - 1) * PAGE_SIZE).coerceAtLeast(0)
        val pageItems = channels
            .drop(start)
            .take(PAGE_SIZE)
            .map { it.toSearchResponse() }

        return newHomePageResponse(
            HomePageList(request.name, pageItems, isHorizontalImages = true),
            hasNext = channels.size > start + PAGE_SIZE
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim().lowercase(Locale.ROOT)
        if (keyword.length < 2) return emptyList()

        val matches = mutableListOf<LiveChannel>()
        for (country in countries) {
            val countryChannels = runCatching { channelsFor(country) }.getOrDefault(emptyList())
            matches += countryChannels.filter { channel ->
                channel.name.lowercase(Locale.ROOT).contains(keyword) ||
                    channel.tvgId.lowercase(Locale.ROOT).contains(keyword) ||
                    channel.country.name.lowercase(Locale.ROOT).contains(keyword)
            }
            if (matches.size >= SEARCH_LIMIT * 2) break
        }

        return matches
            .distinctBy { it.stableId }
            .take(SEARCH_LIMIT)
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val countryCode = url.substringAfter("/channel/", "")
            .substringBefore("/")
            .ifBlank { throw ErrorLoadingException("Kode negara channel tidak ditemukan.") }

        val channelId = url.substringAfter("/channel/$countryCode/", "")
            .substringBefore("?")
            .substringBefore("#")
            .decodeUrl()
            .ifBlank { throw ErrorLoadingException("ID channel tidak ditemukan.") }

        val country = countryByCode(countryCode)
            ?: throw ErrorLoadingException("Negara tidak didukung: $countryCode")

        val channel = channelsFor(country).firstOrNull { it.stableId == channelId }
            ?: throw ErrorLoadingException("Channel tidak ditemukan atau terfilter demi safety repo.")

        val safeStreamCount = channel.safeStreams().size
        return newLiveStreamLoadResponse(
            name = channel.displayName,
            url = channel.detailUrl,
            dataUrl = channel.toJson()
        ).apply {
            posterUrl = channel.posterUrl
            plot = "Public Live TV"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channel = LiveChannel.fromJson(data) ?: return false
        val streams = channel.safeStreams()
            .distinctBy { it.streamUrl }

        streams.forEachIndexed { index, stream ->
            val headers = stream.playbackHeaders()
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = buildString {
                        append(channel.name)
                        if (streams.size > 1) append(" - Mirror ").append(index + 1)
                        append(" - Live")
                    },
                    url = stream.streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    quality = stream.quality(channel)
                    referer = headers["Referer"] ?: stream.referrer.ifBlank { mainUrl }
                    this.headers = headers
                }
            )
        }

        return streams.isNotEmpty()
    }

    private suspend fun channelsFor(country: Country): List<LiveChannel> {
        cache[country.code]?.let { return it }

        val logoMap = runCatching { loadLogoMap() }
            .onFailure { logError(it) }
            .getOrDefault(emptyMap())

        val parsed = playlistUrls(country).firstNotNullOfOrNull { playlistUrl ->
            runCatching {
                val text = app.get(
                    playlistUrl,
                    headers = mapOf(
                        "Accept" to "application/vnd.apple.mpegurl,text/plain,*/*",
                        "User-Agent" to USER_AGENT
                    ),
                    timeout = 30L
                ).text

                parseM3u(text, country, logoMap)
                    .filter { it.safeStreams().isNotEmpty() }
                    .takeIf { it.isNotEmpty() }
            }.onFailure { logError(it) }.getOrNull()
        }
            .orEmpty()
            .sortedWith(compareBy({ it.groupTitle.ifBlank { "~" } }, { it.name }))

        cache[country.code] = parsed
        return parsed
    }

    private fun playlistUrls(country: Country): List<String> {
        return listOf(
            // Official IPTV-org generated country playlist. This usually carries enriched tvg-logo metadata.
            "https://iptv-org.github.io/iptv/countries/${country.code}.m3u",
            // Source playlist fallback. This often has stream headers but may not carry tvg-logo.
            "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/${country.code}.m3u"
        )
    }

    private suspend fun loadLogoMap(): Map<String, String> {
        if (logoCache.isNotEmpty()) return logoCache

        loadChannelApiLogos()
        loadLogosApiLogos()
        CURATED_LOGOS.forEach { (id, logo) ->
            logo.toSafePosterUrl()?.let { safeLogo ->
                logoCache.putIfAbsent(id, safeLogo)
            }
        }

        return logoCache
    }

    private suspend fun loadChannelApiLogos() {
        val text = app.get(
            "https://iptv-org.github.io/api/channels.json",
            headers = mapOf(
                "Accept" to "application/json,text/plain,*/*",
                "User-Agent" to USER_AGENT
            ),
            timeout = 30L
        ).text

        val array = JSONArray(text)
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            val logo = item.firstSafeUrl("logo", "logo_url", "image", "icon")
            putLogoAliases(id, logo)
        }
    }

    private suspend fun loadLogosApiLogos() {
        val text = runCatching {
            app.get(
                "https://iptv-org.github.io/api/logos.json",
                headers = mapOf(
                    "Accept" to "application/json,text/plain,*/*",
                    "User-Agent" to USER_AGENT
                ),
                timeout = 30L
            ).text
        }.getOrNull() ?: return

        val array = JSONArray(text)
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.firstNonBlank("channel", "channel_id", "tvg_id", "id")
            val logo = item.firstSafeUrl("url", "logo", "logo_url", "image", "src")
            putLogoAliases(id, logo)
        }
    }

    private fun putLogoAliases(rawId: String, rawLogo: String?) {
        val logo = rawLogo?.toSafePosterUrl() ?: return
        val id = rawId.trim()
        if (id.isBlank()) return

        listOf(
            id,
            id.baseTvgId(),
            id.substringBefore(".").trim(),
            id.baseTvgId().substringBefore(".").trim()
        )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { alias -> logoCache.putIfAbsent(alias, logo) }
    }

    private fun parseM3u(
        text: String,
        country: Country,
        logoMap: Map<String, String>
    ): List<LiveChannel> {
        val entries = mutableListOf<ParsedEntry>()
        var currentInfo: String? = null
        var currentName: String? = null
        var currentAttrs: Map<String, String> = emptyMap()
        var pendingReferrer = ""
        var pendingUserAgent = ""

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    currentInfo = line
                    currentName = line.substringAfterLast(',', missingDelimiterValue = "").trim()
                    currentAttrs = parseAttributes(line)
                    pendingReferrer = ""
                    pendingUserAgent = ""
                }

                line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) -> {
                    pendingReferrer = line.substringAfter("=", "").trim()
                }

                line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) -> {
                    pendingUserAgent = line.substringAfter("=", "").trim()
                }

                line.startsWith("http://", ignoreCase = true) || line.startsWith("https://", ignoreCase = true) -> {
                    val tvgId = currentAttrs["tvg-id"].orEmpty()
                    val baseTvgId = tvgId.baseTvgId()
                    val rawName = currentName.orEmpty().ifBlank { tvgId }
                    val cleanName = rawName.cleanChannelName()
                    val logo = resolveLogo(
                        explicitLogo = currentAttrs["tvg-logo"].orEmpty(),
                        tvgId = tvgId,
                        cleanName = cleanName,
                        rawName = rawName,
                        logoMap = logoMap
                    )

                    if (cleanName.isNotBlank()) {
                        entries += ParsedEntry(
                            stableId = stableChannelId(
                                tvgId = tvgId,
                                name = cleanName,
                                groupTitle = currentAttrs["group-title"].orEmpty()
                            ),
                            name = cleanName,
                            rawName = rawName,
                            tvgId = tvgId,
                            tvgLogo = logo,
                            groupTitle = currentAttrs["group-title"].orEmpty(),
                            stream = LiveStream(
                                streamUrl = line,
                                referrer = pendingReferrer,
                                userAgent = pendingUserAgent,
                                label = currentInfo.orEmpty().extractBracketLabels(),
                                rawInfo = currentInfo.orEmpty()
                            )
                        )
                    }
                    currentInfo = null
                    currentName = null
                    currentAttrs = emptyMap()
                    pendingReferrer = ""
                    pendingUserAgent = ""
                }
            }
        }

        return entries
            .groupBy { it.stableId }
            .mapNotNull { (_, group) ->
                val first = group.firstOrNull() ?: return@mapNotNull null
                val logo = group.firstNotNullOfOrNull { it.tvgLogo.takeIf { value -> value.isNotBlank() } }.orEmpty()
                LiveChannel(
                    name = first.name,
                    rawName = first.rawName,
                    tvgId = first.tvgId,
                    tvgLogo = logo,
                    groupTitle = first.groupTitle,
                    country = country,
                    streams = group.map { it.stream }
                        .filter { it.isRepoSafe(first.name, first.rawName, first.tvgId, first.groupTitle) }
                        .distinctBy { it.streamUrl }
                )
            }
            .filter { it.streams.isNotEmpty() }
            .distinctBy { it.stableId }
    }

    private fun resolveLogo(
        explicitLogo: String,
        tvgId: String,
        cleanName: String,
        rawName: String,
        logoMap: Map<String, String>
    ): String {
        val fallbackName = cleanName.ifBlank { rawName.cleanChannelName() }.ifBlank { tvgId.baseTvgId() }.ifBlank { "Live TV" }

        explicitLogo.toLogoPosterUrl(fallbackName)?.let { return it }

        val candidates = buildList {
            add(tvgId)
            add(tvgId.baseTvgId())
            add(tvgId.substringBefore(".").trim())
            add(tvgId.baseTvgId().substringBefore(".").trim())
            add(cleanName.logoKey())
            add(rawName.cleanChannelName().logoKey())
        }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        for (candidate in candidates) {
            logoMap[candidate]?.toLogoPosterUrl(fallbackName)?.let { return it }
            CURATED_LOGOS[candidate]?.toLogoPosterUrl(fallbackName)?.let { return it }
        }

        // Last-resort poster is still channel-specific: a generated card with the channel name.
        // This keeps every card visual non-blank without using random favicons or unrelated brand logos.
        return fallbackLogoTile(fallbackName)
    }

    private fun parseAttributes(line: String): Map<String, String> {
        return ATTR_REGEX.findAll(line)
            .associate { match -> match.groupValues[1].lowercase(Locale.ROOT) to match.groupValues[2].trim() }
    }

    private fun countryByCode(code: String): Country? {
        return countries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }

    private fun LiveChannel.toSearchResponse(): SearchResponse {
        return newLiveSearchResponse(
            name = displayName,
            url = detailUrl,
            type = TvType.Live
        ).apply {
            posterUrl = this@toSearchResponse.posterUrl
            // Do not map country codes to SearchResponse.lang.
            // CloudStream treats lang as a language code, so country codes like my/sg/kr can display wrong flags.
            lang = null
        }
    }

    private data class Country(
        val code: String,
        val name: String,
        val flag: String
    )

    private data class ParsedEntry(
        val stableId: String,
        val name: String,
        val rawName: String,
        val tvgId: String,
        val tvgLogo: String,
        val groupTitle: String,
        val stream: LiveStream
    )

    private data class LiveChannel(
        val name: String,
        val rawName: String,
        val tvgId: String,
        val tvgLogo: String,
        val groupTitle: String,
        val country: Country,
        val streams: List<LiveStream>
    ) {
        val stableId: String by lazy {
            stableChannelId(tvgId, name, groupTitle)
        }

        val displayName: String by lazy {
            buildString {
                append(country.flag).append(" ").append(name)
                append(" • ").append(country.name)
            }.trim()
        }

        val detailUrl: String by lazy {
            "https://globetv.app/channel/${country.code}/${stableId.encodeUrl()}"
        }

        val posterUrl: String? by lazy {
            tvgLogo.toSafePosterUrl() ?: fallbackLogoTile(name)
        }

        fun safeStreams(): List<LiveStream> {
            return streams.filter { stream ->
                stream.isRepoSafe(name, rawName, tvgId, groupTitle)
            }
        }

        fun toJson(): String {
            return JSONObject().apply {
                put("name", name)
                put("rawName", rawName)
                put("tvgId", tvgId)
                put("tvgLogo", tvgLogo)
                put("groupTitle", groupTitle)
                put("countryCode", country.code)
                put("countryName", country.name)
                put("countryFlag", country.flag)
                put(
                    "streams",
                    JSONArray().apply {
                        streams.forEach { stream ->
                            put(stream.toJsonObject())
                        }
                    }
                )
            }.toString()
        }

        companion object {
            fun fromJson(text: String): LiveChannel? {
                return runCatching {
                    val json = JSONObject(text)
                    val country = Country(
                        code = json.optString("countryCode"),
                        name = json.optString("countryName"),
                        flag = json.optString("countryFlag")
                    )
                    val array = json.optJSONArray("streams") ?: JSONArray()
                    val streams = buildList {
                        for (index in 0 until array.length()) {
                            val stream = LiveStream.fromJsonObject(array.optJSONObject(index) ?: continue)
                            add(stream)
                        }
                    }
                    LiveChannel(
                        name = json.optString("name"),
                        rawName = json.optString("rawName"),
                        tvgId = json.optString("tvgId"),
                        tvgLogo = json.optString("tvgLogo"),
                        groupTitle = json.optString("groupTitle"),
                        country = country,
                        streams = streams
                    )
                }.getOrNull()
            }
        }
    }

    private data class LiveStream(
        val streamUrl: String,
        val referrer: String,
        val userAgent: String,
        val label: String,
        val rawInfo: String
    ) {
        fun isRepoSafe(
            channelName: String,
            rawName: String,
            tvgId: String,
            groupTitle: String
        ): Boolean {
            val url = streamUrl.trim()
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return false
            if (!url.lowercase(Locale.ROOT).substringBefore("?").endsWith(".m3u8")) return false

            val haystack = listOf(channelName, rawName, tvgId, groupTitle, label, rawInfo, streamUrl)
                .joinToString(" ")
                .lowercase(Locale.ROOT)

            if (BLOCKED_FLAGS.any { haystack.contains(it) }) return false
            if (PREMIUM_OR_RISKY_KEYWORDS.any { haystack.contains(it) }) return false
            if (ADULT_KEYWORD_REGEX.containsMatchIn(haystack)) return false
            if (SUSPICIOUS_URL_PARTS.any { streamUrl.lowercase(Locale.ROOT).contains(it) }) return false
            if (CREDENTIAL_URL_REGEX.containsMatchIn(streamUrl)) return false

            return true
        }

        fun playbackHeaders(): Map<String, String> {
            val origin = referrer.originOrNull()
            return buildMap {
                put("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,*/*")
                put("User-Agent", userAgent.ifBlank { USER_AGENT })
                if (referrer.isNotBlank()) put("Referer", referrer)
                if (!origin.isNullOrBlank()) put("Origin", origin)
            }
        }

        fun quality(channel: LiveChannel): Int {
            val text = "${channel.rawName} ${channel.name} $label $rawInfo $streamUrl".lowercase(Locale.ROOT)
            return when {
                text.contains("2160") || text.contains("4k") -> Qualities.P2160.value
                text.contains("1440") -> Qualities.P1440.value
                text.contains("1080") -> Qualities.P1080.value
                text.contains("720") -> Qualities.P720.value
                text.contains("576") -> Qualities.P480.value
                text.contains("480") -> Qualities.P480.value
                text.contains("360") -> Qualities.P360.value
                text.contains("240") -> Qualities.P240.value
                else -> Qualities.Unknown.value
            }
        }

        fun toJsonObject(): JSONObject {
            return JSONObject().apply {
                put("streamUrl", streamUrl)
                put("referrer", referrer)
                put("userAgent", userAgent)
                put("label", label)
                put("rawInfo", rawInfo)
            }
        }

        companion object {
            fun fromJsonObject(json: JSONObject): LiveStream {
                return LiveStream(
                    streamUrl = json.optString("streamUrl"),
                    referrer = json.optString("referrer"),
                    userAgent = json.optString("userAgent"),
                    label = json.optString("label"),
                    rawInfo = json.optString("rawInfo")
                )
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 60
        private const val SEARCH_LIMIT = 80

        private val countries = listOf(
            Country("id", "Indonesia", "🇮🇩"),
            Country("ph", "Philippines", "🇵🇭"),
            Country("th", "Thailand", "🇹🇭"),
            Country("jp", "Japan", "🇯🇵"),
            Country("kr", "South Korea", "🇰🇷"),
            Country("in", "India", "🇮🇳"),
            Country("us", "United States", "🇺🇸")
        )

        private val ATTR_REGEX = Regex("""([a-zA-Z0-9_-]+)="([^"]*)"""")

        private val BLOCKED_FLAGS = listOf(
            "[geo-blocked]",
            "geo-blocked",
            "[not 24/7]",
            "not 24/7",
            "drm",
            "widevine",
            "login required",
            "subscription",
            "premium"
        )

        private val PREMIUM_OR_RISKY_KEYWORDS = listOf(
            "bein", "beinsports", "spotv", "espn", "fox sports", "sky sports", "bt sport",
            "dazn", "eurosport", "supersport", "premier league", "champions league", "laliga",
            "serie a", "bundesliga", "nba", "nfl", "mlb", "ufc", "wwe", "f1", "formula 1",
            "hbo", "cinemax", "showtime", "starz", "disney", "netflix", "prime video", "paramount",
            "cinema", "bioskop", "movie channel", "pay-per-view", "ppv", "astro supersport",
            "fox movies", "warner tv", "axn", "thrill", "hits movies"
        )

        private val ADULT_KEYWORD_REGEX = Regex(
            """(^|[^a-z0-9])(adult|xxx|porn|sex|erotic|playboy|brazzers|redtube)([^a-z0-9]|$)""",
            RegexOption.IGNORE_CASE
        )

        private val SUSPICIOUS_URL_PARTS = listOf(
            "primestreams", "xtream", "stalker", "get.php?username=", "/movie/", "/series/",
            "/live/mookie", "m3u_plus", "type=m3u", "output=ts", "output=m3u8"
        )

        private val CURATED_LOGOS = buildMap {
            fun putLogo(url: String, vararg aliases: String) {
                aliases.forEach { alias ->
                    val cleanAlias = alias.trim()
                    if (cleanAlias.isNotBlank()) {
                        putIfAbsent(cleanAlias, url)
                        putIfAbsent(cleanAlias.logoKey(), url)
                    }
                }
            }


            // Curated refinement: direct channel-logo sources for entries that previously fell back to text tiles.
            // Scope: logo aliases only. Category/search/loadLinks are intentionally untouched.
            putLogo("https://en.wikipedia.org/wiki/Special:Redirect/file/SelangorTVlogo.png", "SelangorTV.my", "Selangor TV", "SelangorTV")
            putLogo("https://en.wikipedia.org/wiki/Special:Redirect/file/Mediacorp_Channel_U_2023.svg", "ChannelU.sg", "Channel U", "Mediacorp Channel U")
            putLogo("https://en.wikipedia.org/wiki/Special:Redirect/file/BilyonaryoNewsChannel_Logo_-_Black_%282024%29.png", "BilyonaryoNewsChannel.ph", "Bilyonaryo News Channel", "Bilyonaryo News", "BNC")
            putLogo("https://en.wikipedia.org/wiki/Special:Redirect/file/ALTV_Logo.png", "ALTV.th", "ALTV", "ALT TV")
            putLogo("https://logo.clearbit.com/bnc.ph", "BNC.ph", "Bilyonaryo TV")
            putLogo("https://logo.clearbit.com/dltv.ac.th", "DLTV.th", "DLTV", "DLTV 1", "DLTV1")
            putLogo("https://logo.clearbit.com/filamtv.com", "FilAmTV.ph", "FilAmTV", "FilAmTV Network", "FilAm TV Network")

            // Curated official-domain logo fallbacks. These are only used when IPTV-org metadata has no logo.
            // They intentionally use channel/owner official domains, not CDN stream host favicons.
            putLogo("https://logo.clearbit.com/antvklik.com", "ANTV.id", "ANTV", "ANTV HD")
            putLogo("https://logo.clearbit.com/aliman.id", "AlImanTV.id", "Al-Iman TV", "Al Iman TV")
            putLogo("https://logo.clearbit.com/tvri.go.id", "TVRI.id", "TVRI", "TVRI Sport", "TVRISport.id", "TVRIWorld.id", "TVRI World")
            putLogo("https://logo.clearbit.com/metroTVnews.com", "MetroTV.id", "Metro TV")
            putLogo("https://logo.clearbit.com/trans7.co.id", "Trans7.id", "Trans7")
            putLogo("https://logo.clearbit.com/transtv.co.id", "TransTV.id", "Trans TV")
            putLogo("https://logo.clearbit.com/cnbcindonesia.com", "CNBCIndonesia.id", "CNBC Indonesia")
            putLogo("https://logo.clearbit.com/theindonesiachannel.com", "TheIndonesiaChannel.id", "The Indonesia Channel", "Indonesia Channel")
            putLogo("https://logo.clearbit.com/garudatv.id", "GarudaTV.id", "Garuda TV")
            putLogo("https://logo.clearbit.com/daaitv.co.id", "DAAITV.id", "DAAI TV")
            putLogo("https://logo.clearbit.com/beritasatu.com", "BeritaSatu.id", "BeritaSatu", "BTV.id", "BTV")
            putLogo("https://logo.clearbit.com/tvonenews.com", "tvOne.id", "tvOne", "TV One")
            putLogo("https://logo.clearbit.com/kompas.tv", "KompasTV.id", "Kompas TV")
            putLogo("https://logo.clearbit.com/rtv.co.id", "RajawaliTV.id", "Rajawali TV", "RTV")
            putLogo("https://logo.clearbit.com/rctiplus.com", "RCTI.id", "RCTI", "MNCTV.id", "MNCTV", "iNews.id", "iNews")
            putLogo("https://logo.clearbit.com/indosiar.com", "Indosiar.id", "Indosiar")
            putLogo("https://logo.clearbit.com/sctv.co.id", "SCTV.id", "SCTV")
            putLogo("https://logo.clearbit.com/netmedia.co.id", "NET.id", "NET TV")
            putLogo("https://logo.clearbit.com/rodjatv.com", "RodjaTV.id", "Rodja TV")
            putLogo("https://logo.clearbit.com/nusantaratv.com", "NusantaraTV.id", "Nusantara TV")
            putLogo("https://logo.clearbit.com/tvmu.tv", "TVMu.id", "TV Mu")

            putLogo("https://logo.clearbit.com/8tv.com.my", "8TV.my", "8 TV", "8TV")
            putLogo("https://logo.clearbit.com/tv3.com.my", "TV3.my", "TV3")
            putLogo("https://logo.clearbit.com/ntv7.com.my", "NTV7.my", "NTV7")
            putLogo("https://logo.clearbit.com/tonton.com.my", "TV9.my", "TV9")
            putLogo("https://logo.clearbit.com/astroawani.com", "AstroAwani.my", "Astro Awani")
            putLogo("https://logo.clearbit.com/bernama.com", "BernamaTV.my", "Bernama TV")
            putLogo("https://logo.clearbit.com/selangortv.my", "SelangorTV.my", "Selangor TV", "SelangorTV")
            putLogo("https://logo.clearbit.com/maah.tv", "MaahTV.my", "Maah TV")

            putLogo("https://logo.clearbit.com/mediacorp.sg", "Channel8.sg", "Channel 8", "ChannelU.sg", "Channel U", "CNA.sg", "CNA")
            putLogo("https://logo.clearbit.com/meWatch.sg", "Channel5.sg", "Channel 5")

            putLogo("https://logo.clearbit.com/abs-cbn.com", "ANC.ph", "ANC", "KapamilyaChannel.ph", "Kapamilya Channel")
            putLogo("https://logo.clearbit.com/gmanetwork.com", "GMA.ph", "GMA", "GTV.ph", "GTV")
            putLogo("https://logo.clearbit.com/ptvnews.ph", "PTV.ph", "PTV")

            putLogo("https://logo.clearbit.com/nhk.or.jp", "NHKWorldJapan.jp", "NHK World Japan", "NHK.jp", "NHK")
            putLogo("https://logo.clearbit.com/arirang.com", "ArirangTV.kr", "Arirang TV")
            putLogo("https://logo.clearbit.com/kbs.co.kr", "KBSWorld.kr", "KBS World", "KBS.kr", "KBS")
            putLogo("https://logo.clearbit.com/mbc.co.kr", "MBC.kr", "MBC")
            putLogo("https://logo.clearbit.com/sbs.co.kr", "SBS.kr", "SBS")

            putLogo("https://logo.clearbit.com/pbs.org", "PBS.us", "PBS")
            putLogo("https://logo.clearbit.com/cbsnews.com", "CBSNews.us", "CBS News")
            putLogo("https://logo.clearbit.com/nbcnews.com", "NBCNews.us", "NBC News")
            putLogo("https://logo.clearbit.com/abcnews.go.com", "ABCNews.us", "ABC News")
            putLogo("https://logo.clearbit.com/bloomberg.com", "BloombergTV.us", "Bloomberg TV", "Bloomberg")
        }

        private val CREDENTIAL_URL_REGEX = Regex("""https?://[^/\s]+:[^/\s]+@""", RegexOption.IGNORE_CASE)
    }
}

private fun String.cleanChannelName(): String {
    return replace(Regex("""\[[^\]]+]"""), "")
        .replace(Regex("""\([^)]*p\)""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun stableChannelId(
    tvgId: String,
    name: String,
    groupTitle: String
): String {
    return listOf(tvgId.baseTvgId(), name, groupTitle)
        .joinToString("|")
        .ifBlank { name }
}

private fun String.baseTvgId(): String {
    return substringBefore("@")
        .trim()
}

private fun String.toSafePosterUrl(): String? {
    val clean = trim()
    if (clean.isBlank()) return null
    return when {
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true) -> clean
        else -> null
    }
}

private fun String.toLogoPosterUrl(fallbackName: String): String? {
    val clean = toSafePosterUrl() ?: return null
    if (clean.contains("placehold.co", ignoreCase = true)) return clean

    val source = clean
        .removePrefix("https://")
        .removePrefix("http://")
        .trim()
    if (source.isBlank()) return fallbackLogoTile(fallbackName)

    val fallbackSource = fallbackLogoTile(fallbackName)
        .removePrefix("https://")
        .removePrefix("http://")

    // Pad logos into a 16:9 canvas to avoid CloudStream card center-crop cutting large/wide TV logos.
    // The default parameter gives a channel-name tile if the upstream logo URL fails.
    return "https://images.weserv.nl/?url=${source.encodeUrl()}&w=640&h=360&fit=contain&output=png&bg=00000000&default=${fallbackSource.encodeUrl()}"
}

private fun fallbackLogoTile(channelName: String): String {
    val label = channelName.cleanChannelName()
        .ifBlank { "Live TV" }
        .take(40)
    return "https://placehold.co/640x360/111111/FFFFFF/png?text=${label.encodeUrl()}"
}

private fun String.extractBracketLabels(): String {
    return Regex("""\[[^\]]+]""")
        .findAll(this)
        .map { it.value }
        .filterNot { label ->
            val lower = label.lowercase(Locale.ROOT)
            lower.contains("not 24/7") || lower.contains("geo-blocked")
        }
        .joinToString(" ")
        .trim()
}

private fun String.encodeUrl(): String {
    return URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}

private fun String.decodeUrl(): String {
    return runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)
}

private fun JSONObject.firstNonBlank(vararg keys: String): String {
    return keys.firstNotNullOfOrNull { key ->
        optString(key).trim().takeIf { it.isNotBlank() }
    }.orEmpty()
}

private fun JSONObject.firstSafeUrl(vararg keys: String): String? {
    return keys.firstNotNullOfOrNull { key ->
        optString(key).trim().toSafePosterUrl()
    }
}

private fun String.logoKey(): String {
    return cleanChannelName()
        .lowercase(Locale.ROOT)
        .replace("&", "and")
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun String.originOrNull(): String? {
    return runCatching {
        val uri = URI(this)
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        "$scheme://$host$port"
    }.getOrNull()
}
