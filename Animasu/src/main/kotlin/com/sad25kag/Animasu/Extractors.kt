package com.sad25kag.Animasu

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import org.json.JSONObject
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

class Archivd : ExtractorApi() {

    override val name: String = "Archivd"
    override val mainUrl: String = "https://archivd.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url).document
        val json = res.selectFirst("div#app")?.attr("data-page")

        if (json.isNullOrBlank()) return

        val video = runCatching {
            JSONObject(json)
                .optJSONObject("props")
                ?.optJSONObject("datas")
                ?.optJSONObject("data")
                ?.optJSONObject("link")
                ?.optString("media")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

        if (video.isNullOrBlank()) return

        callback.invoke(
            newExtractorLink(
                name,
                name,
                video,
                INFER_TYPE
            ) {
                this.referer = "$mainUrl/"
            }
        )
    }

    data class Link(
        @param:JsonProperty("media")
        val media: String? = null,
    )

    data class Data(
        @param:JsonProperty("link")
        val link: Link? = null,
    )

    data class Datas(
        @param:JsonProperty("data")
        val data: Data? = null,
    )

    data class Props(
        @param:JsonProperty("datas")
        val datas: Datas? = null,
    )

    data class Sources(
        @param:JsonProperty("props")
        val props: Props? = null,
    )
}

class Newuservideo : ExtractorApi() {

    override val name: String = "Uservideo"
    override val mainUrl: String = "https://new.uservideo.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headersMap = referer?.let { mapOf("Referer" to it) } ?: emptyMap()

        val iframeSrc = app.get(url, headers = headersMap)
            .document
            .selectFirst("iframe#videoFrame")
            ?.attr("src")

        if (iframeSrc.isNullOrBlank()) return

        val iframeUrl = if (iframeSrc.startsWith("http")) {
            iframeSrc
        } else {
            "$mainUrl$iframeSrc"
        }

        val doc = app.get(
            iframeUrl,
            headers = mapOf("Referer" to "$mainUrl/")
        ).text

        val json = Regex("""VIDEO_CONFIGs*=s*({.*?})""")
            .find(doc)
            ?.groupValues
            ?.get(1)
            ?: Regex("""VIDEO_CONFIGs*=s*(.*)""")
                .find(doc)
                ?.groupValues
                ?.get(1)

        if (json.isNullOrBlank()) return

        val streams = runCatching {
            JSONObject(json).optJSONArray("streams")
        }.getOrNull() ?: return

        for (index in 0 until streams.length()) {
            val stream = streams.optJSONObject(index) ?: continue
            val playUrl = stream.optString("play_url").takeIf { it.isNotBlank() } ?: continue
            val formatId = stream.optInt("format_id", -1)

            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    playUrl,
                    INFER_TYPE
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = when (formatId) {
                        18 -> Qualities.P360.value
                        22 -> Qualities.P720.value
                        else -> Qualities.Unknown.value
                    }
                }
            )
        }
    }

    data class Streams(
        @param:JsonProperty("play_url")
        val playUrl: String? = null,

        @param:JsonProperty("format_id")
        val formatId: Int? = null,
    )

    data class Sources(
        @param:JsonProperty("streams")
        val streams: ArrayList<Streams>? = null,
    )
}

class Vidhidepro : Filesim() {

    override val mainUrl = "https://vidhidepro.com"
    override val name = "Vidhidepro"
}

// NOTE: berkasdrive.com is a GDrive-style file-hosting mirror used across several
// Muvipro/Dooplay-based sites in this project. This mirrors the more mature
// implementation already used by DramaIdProvider (dl.berkasdrive.com/streaming?id=...
// base64-encoded resolver links, plus a fallback scan of the page for direct/known
// server URLs).
class BerkasDrive : ExtractorApi() {
    override val name = "BerkasDrive"
    override val mainUrl = "https://dl.berkasdrive.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = normalizeHostUrl(url, mainUrl) ?: return
        val document = app.get(
            fixedUrl,
            referer = referer ?: "$mainUrl/",
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document

        val emitted = linkedSetOf<String>()

        suspend fun addDirect(sourceUrl: String, label: String) {
            if (!emitted.add(sourceUrl)) return
            callback(
                newExtractorLink(
                    name,
                    "$name ${label.ifBlank { "Server" }}",
                    sourceUrl,
                    if (sourceUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = fixedUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Referer" to fixedUrl,
                        "Range" to "bytes=0-",
                        "User-Agent" to USER_AGENT,
                    )
                }
            )
        }

        document.select("video source[src], video[src], .daftar_server li[data-url], [data-url], [data-src], source[src]").forEach { element ->
            val sourceUrl = listOf(
                element.attr("abs:src"),
                element.attr("src"),
                element.attr("data-url"),
                element.attr("data-src"),
            ).firstOrNull { it.isNotBlank() }
                ?.let { normalizeHostUrl(it, fixedUrl) }
                ?: return@forEach

            if (sourceUrl.isDirectMediaHostUrl()) {
                addDirect(sourceUrl, element.text())
            } else {
                loadExtractor(sourceUrl, fixedUrl, subtitleCallback, callback)
            }
        }

        decodeBerkasDriveId(fixedUrl)?.let { resolverUrl ->
            loadExtractor(resolverUrl, fixedUrl, subtitleCallback, callback)
        }
    }

    private fun decodeBerkasDriveId(url: String): String? {
        if (!url.contains("dl.berkasdrive.com/streaming", true)) return null
        return url.substringAfter("?", "")
            .split("&")
            .firstOrNull { it.substringBefore("=") == "id" }
            ?.substringAfter("=")
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?.let(::decodeBase64Text)
            ?.let { normalizeHostUrl(it, mainUrl) }
    }
}

// NOTE: mitedrive.my.id appears to be a rebrand/re-domain of the older
// mitedrive.com host used elsewhere in this project (Nimegami), which resolves
// via a POST to an /api/view/{id} endpoint returning the original file URL.
// Since the site could not be inspected directly, the endpoint below mirrors
// that known pattern under the new domain - verify against a real Network capture
// and adjust the API host/path if it 404s.
class Mitedrive : ExtractorApi() {
    override val name = "Mitedrive"
    override val mainUrl = "https://mitedrive.my.id"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.substringAfterLast("/").substringBefore("?").trim()
        if (id.isBlank()) return

        val video = runCatching {
            app.post(
                "https://api.mitedrive.my.id/api/view/$id",
                referer = "$mainUrl/",
                data = mapOf("slug" to id),
                headers = mapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "User-Agent" to USER_AGENT,
                )
            ).parsedSafe<MitedriveResponse>()?.data?.url
        }.getOrNull() ?: return

        callback.invoke(
            newExtractorLink(
                name,
                name,
                video,
                INFER_TYPE,
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            }
        )
    }

    data class MitedriveData(
        @param:JsonProperty("original_url") val url: String? = null,
    )

    data class MitedriveResponse(
        @param:JsonProperty("data") val data: MitedriveData? = null,
    )
}

private fun normalizeHostUrl(raw: String, baseUrl: String): String? {
    val clean = Jsoup.parse(raw).text()
        .trim()
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

private fun decodeBase64Text(value: String): String? {
    val clean = value.trim().replace("\\s".toRegex(), "")
    if (clean.isBlank()) return null
    val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
    return runCatching { String(Base64.getDecoder().decode(padded)) }.getOrNull()
}

private fun String.isDirectMediaHostUrl(): Boolean {
    return Regex("""(?i)\.(mp4|m3u8)(?:$|[?#&])""").containsMatchIn(this)
}
