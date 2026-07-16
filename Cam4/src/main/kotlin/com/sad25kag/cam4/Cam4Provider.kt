package com.sad25kag.cam4

import android.net.Uri
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLEncoder

class Cam4Provider : MainAPI() {
    override var mainUrl = "https://www.cam4.com"

    private val baseReferer = "$mainUrl/"
    override var name = "Cam4"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "/api/directoryCams?directoryJson=true&online=true&url=true&orderBy=VIDEO_QUALITY" to "Semua",
        "/api/directoryCams?directoryJson=true&online=true&url=true&orderBy=NEWEST" to "Terbaru",
        "/api/directoryCams?directoryJson=true&online=true&url=true&orderBy=VIDEO_QUALITY&gender=female&broadcastType=female_group&broadcastType=solo&broadcastType=male_female_group" to "Perempuan",
        "/api/directoryCams?directoryJson=true&online=true&url=true&orderBy=VIDEO_QUALITY&broadcastType=male_group&broadcastType=female_group&broadcastType=male_female_group" to "Pasangan"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPagedUrl(request.data, page)

        val response = app.get(
            url,
            headers = defaultHeaders,
            referer = baseReferer
        ).parsedSafe<Response>()

        val items = response?.users.orEmpty()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/api/directoryCams?directoryJson=true&online=true&url=true&search=$encodedQuery&orderBy=VIDEO_QUALITY"

        val response = app.get(
            searchUrl,
            headers = defaultHeaders,
            referer = baseReferer
        ).parsedSafe<Response>() ?: return emptyList()

        return response.users
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val username = extractUsername(url)
            ?: throw ErrorLoadingException("Username tidak ditemukan.")

        val document = app.get(
            "$mainUrl/$username",
            headers = htmlHeaders,
            referer = baseReferer
        ).document

        val title = document.selectFirst("meta[property=og:title]")
            ?.attr("content")
            ?.trim()
            ?.ifBlank { null }
            ?: username

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image], [property='og:image']")
                ?.attr("content")
        )

        val description = document.selectFirst("meta[property=og:description]")
            ?.attr("content")
            ?.trim()
            ?.ifBlank { null }

        return newLiveStreamLoadResponse(
            name = title,
            url = "$mainUrl/$username",
            dataUrl = "$mainUrl/$username",
        ).apply {
            this.posterUrl = poster
            this.plot = description ?: "Live stream Cam4"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val username = extractUsername(data) ?: return false
        val streamInfoUrl = "$mainUrl/rest/v1.0/profile/$username/streamInfo"

        val responseText = app.get(
            streamInfoUrl,
            headers = defaultHeaders,
            referer = "$mainUrl/$username"
        ).text

        val json = runCatching { JSONObject(responseText) }.getOrNull() ?: return false

        val streamUrl = listOf(
            "cdnURL",
            "cdnUrl",
            "hlsUrl",
            "streamUrl",
            "url"
        ).firstNotNullOfOrNull { key ->
            json.optString(key).takeIf { it.isNotBlank() }
        } ?: return false

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name - Live",
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/$username"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "Accept" to "*/*",
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/$username",
                    "Origin" to mainUrl
                )
            }
        )

        return true
    }

    private fun buildPagedUrl(path: String, page: Int): String {
        val joiner = if (path.contains("?")) "&" else "?"
        return "$mainUrl$path${joiner}page=$page"
    }

    private fun extractUsername(raw: String): String? {
        val parsed = runCatching { Uri.parse(raw) }.getOrNull()

        parsed?.host?.let { host ->
            if (!host.equals("cam4.com", ignoreCase = true) &&
                !host.endsWith(".cam4.com", ignoreCase = true)) {
                return null
            }
        }

        return parsed?.pathSegments?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: raw.substringAfterLast("/")
                .substringBefore("?")
                .trim()
                .takeIf { it.isNotBlank() }
    }

    private fun User.toSearchResult(): SearchResponse? {
        val cleanUsername = username.trim()
        if (cleanUsername.isBlank()) return null

        return newLiveSearchResponse(
            name = cleanUsername,
            url = "$mainUrl/$cleanUsername",
            type = TvType.Live,
        ).apply {
            this.posterUrl = snapshotImageLink.takeIf { it.isNotBlank() }
            this.lang = null
        }
    }

    data class User(
        @JsonProperty("username") val username: String = "",
        @JsonProperty("snapshotImageLink") val snapshotImageLink: String = "",
        @JsonProperty("userId") val userId: String = "",
    )

    data class Response(
        @JsonProperty("users") val users: List<User> = emptyList()
    )

    private val defaultHeaders = mapOf(
        "Accept" to "application/json,text/plain,*/*",
        "User-Agent" to USER_AGENT,
        "Referer" to baseReferer,
        "Origin" to mainUrl,
    )

    private val htmlHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "User-Agent" to USER_AGENT,
        "Referer" to baseReferer,
    )
}
