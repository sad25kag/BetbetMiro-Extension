package com.sad25kag.hidoristream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

class Movearnpre : Dingtezuni() {
    override var name = "Movearnpre"
    override var mainUrl = "https://movearnpre.com"
}

class Minochinos : Dingtezuni() {
    override var name = "Minochinos"
    override var mainUrl = "https://minochinos.com"
}

class Mivalyo : Dingtezuni() {
    override var name = "Mivalyo"
    override var mainUrl = "https://mivalyo.com"
}

class Ryderjet : Dingtezuni() {
    override var name = "Ryderjet"
    override var mainUrl = "https://ryderjet.com"
}

class Bingezove : Dingtezuni() {
    override var name = "Bingezove"
    override var mainUrl = "https://bingezove.com"
}

open class Dingtezuni : EarnvidsLike() {
    override var name = "Dingtezuni"
    override var mainUrl = "https://dingtezuni.com"
}

open class Dintezuvio : EarnvidsLike() {
    override var name = "Dintezuvio"
    override var mainUrl = "https://dintezuvio.com"
}

open class EarnvidsLike : ExtractorApi() {
    override var name = "Earnvids"
    override var mainUrl = "https://dingtezuni.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = getEmbedUrl(url)

        val headers = mapOf(
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT
        )

        val response = runCatching {
            app.get(
                embedUrl,
                referer = referer ?: "$mainUrl/",
                headers = headers
            )
        }.getOrNull() ?: return

        val found = linkedSetOf<String>()
        val html = response.text.cleanEscaped()

        extractStreamUrls(html).forEach {
            found.add(normalizeUrl(it, embedUrl))
        }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            val script = if (unpacked.contains("var links")) {
                unpacked.substringAfter("var links")
            } else {
                unpacked
            }.cleanEscaped()

            extractStreamUrls(script).forEach {
                found.add(normalizeUrl(it, embedUrl))
            }
        }

        response.document.selectFirst("script:containsData(sources:)")
            ?.data()
            ?.cleanEscaped()
            ?.let { script ->
                extractStreamUrls(script).forEach {
                    found.add(normalizeUrl(it, embedUrl))
                }
            }

        found.forEach { stream ->
            emitExtractorVideo(name, stream, "$mainUrl/", headers, callback)
        }
    }

    private fun getEmbedUrl(url: String): String {
        return when {
            url.contains("/d/") -> url.replace("/d/", "/v/")
            url.contains("/download/") -> url.replace("/download/", "/v/")
            url.contains("/file/") -> url.replace("/file/", "/v/")
            url.contains("/f/") -> url.replace("/f/", "/v/")
            else -> url
        }
    }
}

class Hglink : StreamWishExtractor() {
    override val name = "Hglink"
    override val mainUrl = "https://hglink.to"
}

class Hgcloud : StreamWishExtractor() {
    override val name = "Hgcloud"
    override val mainUrl = "https://hgcloud.to"
}

class Ghbrisk : StreamWishExtractor() {
    override val name = "Ghbrisk"
    override val mainUrl = "https://ghbrisk.com"
}

class Dhcplay : StreamWishExtractor() {
    override var name = "DHC Play"
    override var mainUrl = "https://dhcplay.com"
}

class Streamcasthub : VidStack() {
    override var name = "Streamcasthub"
    override var mainUrl = "https://live.streamcasthub.store"
    override var requiresReferer = true
}

class Dm21embed : VidStack() {
    override var name = "Dm21embed"
    override var mainUrl = "https://dm21.embed4me.vip"
    override var requiresReferer = true
}

class Dm21upns : VidStack() {
    override var name = "Dm21upns"
    override var mainUrl = "https://dm21.upns.live"
    override var requiresReferer = true
}

class Pm21p2p : VidStack() {
    override var name = "Pm21p2p"
    override var mainUrl = "https://pm21.p2pplay.pro"
    override var requiresReferer = true
}

class Dm21 : VidStack() {
    override var name = "Dm21"
    override var mainUrl = "https://dm21.embed4me.vip"
    override var requiresReferer = true
}

class Meplayer : VidStack() {
    override var name = "Meplayer"
    override var mainUrl = "https://video.4meplayer.com"
    override var requiresReferer = true
}

class Serhmeplayer : VidStack() {
    override var name = "Serhmeplayer"
    override var mainUrl = "https://serh.4meplayer.online"
    override var requiresReferer = true
}

class AbyssPlayer : ExtractorApi() {
    override var name = "AbyssPlayer"
    override var mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Origin" to "https://playhydrax.com",
            "Referer" to "https://playhydrax.com/"
        )

        val document = runCatching {
            app.get(url, headers = headers).document
        }.getOrNull() ?: return

        val scriptData = document.select("script").joinToString("\n") { it.data() }
        val encrypted = Regex("""const\s+datas\s*=\s*["']([^"']+)["']""")
            .find(scriptData)
            ?.groupValues
            ?.getOrNull(1)
            ?: return

        val response = runCatching {
            app.post(
                "https://enc-dec.app/api/dec-abyss",
                headers = headers,
                requestBody = """{"text":"$encrypted"}"""
                    .toRequestBody("application/json".toMediaType())
            ).text
        }.getOrNull() ?: return

        val root = runCatching { JSONObject(response) }.getOrNull() ?: return
        val result = root.optJSONObject("result") ?: root
        val sources = result.optJSONArray("sources") ?: return

        for (i in 0 until sources.length()) {
            val source = sources.optJSONObject(i) ?: continue
            if (!source.optBoolean("status", true)) continue

            val sourceUrl = source.optString("url").trim().cleanEscaped()
            if (sourceUrl.isBlank()) continue

            emitExtractorVideo(
                extractorName = name,
                link = sourceUrl,
                referer = "https://playhydrax.com/",
                headers = headers,
                callback = callback
            )
        }
    }
}

class Veev : ExtractorApi() {
    override val name = "Veev"
    override val mainUrl = "https://veev.to"
    override val requiresReferer = false

    private val pattern = Regex("""(?://|\.)(?:veev|kinoger|poophq|doods)\.(?:to|pw|com)/[ed]/([0-9A-Za-z]+)""")

    companion object {
        const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val mediaId = pattern.find(url)?.groupValues?.getOrNull(1) ?: return
        val pageUrl = "$mainUrl/e/$mediaId"

        val html = runCatching {
            app.get(
                pageUrl,
                headers = mapOf("User-Agent" to DEFAULT_UA)
            ).text
        }.getOrNull() ?: return

        val encRegex = Regex("""[.\s'](?:fc|_vvto\[[^]]*)(?:['\]]*)?\s*[:=]\s*['"]([^'"]+)""")
        val foundValues = encRegex.findAll(html).map { it.groupValues[1] }.toList()

        for (f in foundValues.reversed()) {
            val ch = runCatching { veevDecode(f) }.getOrNull() ?: continue
            if (ch == f) continue

            val dlUrl = "$mainUrl/dl?op=player_api&cmd=gi&file_code=$mediaId&r=$mainUrl&ch=$ch&ie=1"

            val responseText = runCatching {
                app.get(
                    dlUrl,
                    headers = mapOf("User-Agent" to DEFAULT_UA)
                ).text
            }.getOrNull() ?: continue

            val json = runCatching {
                JSONObject(responseText)
            }.getOrNull() ?: continue

            val file = json.optJSONObject("file") ?: continue
            if (file.optString("file_status") != "OK") continue

            val dv = file.optJSONArray("dv")?.optJSONObject(0)?.optString("s") ?: continue
            val rules = buildArray(ch).firstOrNull() ?: emptyList()

            val decoded = runCatching {
                decodeUrl(veevDecode(dv), rules)
            }.getOrNull() ?: continue

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = decoded,
                    type = if (decoded.contains(".m3u8", true)) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                ) {
                    this.referer = mainUrl
                    this.quality = getQualityFromName(decoded).takeIf {
                        it != Qualities.Unknown.value
                    } ?: Qualities.Unknown.value
                }
            )

            return
        }
    }

    private fun veevDecode(etext: String): String {
        if (etext.isEmpty()) return etext

        val result = StringBuilder()
        val lut = HashMap<Int, String>()
        var n = 256
        var c = etext[0].toString()
        result.append(c)

        for (char in etext.drop(1)) {
            val code = char.code
            val nc = if (code < 256) char.toString() else lut[code] ?: (c + c[0])
            result.append(nc)
            lut[n++] = c + nc[0]
            c = nc
        }

        return result.toString()
    }

    private fun jsInt(x: Char): Int = x.digitToIntOrNull() ?: 0

    private fun buildArray(encoded: String): List<List<Int>> {
        val result = mutableListOf<List<Int>>()
        val iterator = encoded.iterator()

        fun nextIntOrZero(): Int {
            return if (iterator.hasNext()) jsInt(iterator.nextChar()) else 0
        }

        var count = nextIntOrZero()

        while (count != 0) {
            val row = mutableListOf<Int>()

            repeat(count) {
                row.add(nextIntOrZero())
            }

            result.add(row.reversed())
            count = nextIntOrZero()
        }

        return result
    }

    private fun decodeUrl(encoded: String, rules: List<Int>): String {
        var text = encoded

        for (rule in rules) {
            if (rule == 1) text = text.reversed()

            val arr = text.chunked(2)
                .mapNotNull { it.toIntOrNull(16)?.toByte() }
                .toByteArray()

            text = arr.toString(Charsets.UTF_8)
                .replace("dXRmOA==", "")
        }

        return text
    }
}

class HidoriStream : ExtractorApi() {
    override val name = "HidoriStream"
    override val mainUrl = "https://stream.hidoristream.my.id"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val sources = linkedSetOf<String>()

        if (isLikelyMediaUrl(url)) {
            sources.add(url)
        } else {
            val response = runCatching {
                app.get(
                    url,
                    referer = referer ?: "https://v8.hidoristream.online/",
                    headers = mapOf("User-Agent" to USER_AGENT)
                )
            }.getOrNull() ?: return

            val html = response.text.cleanEscaped()

            extractStreamUrls(html).forEach { sources.add(normalizeUrl(it, url)) }

            response.document.select("iframe[src], source[src], video[src], a[href*='.m3u8'], a[href*='.mp4'], a[href*='/stream-vid/']")
                .forEach { element ->
                    val raw = element.attr("src").ifBlank { element.attr("href") }.trim()
                    if (raw.isNotBlank()) sources.add(normalizeUrl(raw, url))
                }
        }

        sources.forEach { link ->
            emitExtractorVideo(
                extractorName = name,
                link = link,
                referer = referer ?: "https://v8.hidoristream.online/",
                headers = mapOf("User-Agent" to USER_AGENT),
                callback = callback
            )
        }
    }
}

class Terabox : ExtractorApi() {
    override val name = "Terabox"
    override val mainUrl = "https://1024terabox.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = runCatching {
            app.get(url, referer = referer).text.cleanEscaped()
        }.getOrNull() ?: return

        val videoUrl = Regex(
            """<source[^>]+src=["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""",
            RegexOption.IGNORE_CASE
        ).find(response)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex(
                """(https?://[^\s"',]+?\.(?:mp4|m3u8)[^\s"',]*)""",
                RegexOption.IGNORE_CASE
            ).find(response)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\/", "/")
            ?: Regex(""""browserDownloadUrl"\s*:\s*"([^"]+)"""")
                .find(response)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\/", "/")

        videoUrl?.let { link ->
            emitExtractorVideo(name, link, mainUrl, mapOf("User-Agent" to USER_AGENT), callback)
        }
    }
}

class Buzzheavier : ExtractorApi() {
    override val name = "Buzzheavier"
    override val mainUrl = "https://buzzheavier.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = runCatching {
            app.get(url, referer = referer).document
        }.getOrNull() ?: return

        val videoUrl = document.selectFirst("video source[src], source[src], video[src]")
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("a[href$=.mp4], a[href$=.mkv], a[href$=.m3u8]")
                ?.attr("href")
                ?.takeIf { it.isNotBlank() }
            ?: Regex("""file\s*:\s*["'](https?://[^"']+)["']""")
                .find(document.html())
                ?.groupValues
                ?.getOrNull(1)

        videoUrl?.let { raw ->
            val link = when {
                raw.startsWith("http", true) -> raw
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("/") -> "$mainUrl$raw"
                else -> "$mainUrl/$raw"
            }

            emitExtractorVideo(name, link, mainUrl, mapOf("User-Agent" to USER_AGENT), callback)
        }
    }
}

data class FileSource(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: String? = null
)

private suspend fun emitExtractorVideo(
    extractorName: String,
    link: String,
    referer: String,
    headers: Map<String, String>,
    callback: (ExtractorLink) -> Unit
) {
    if (link.contains(".m3u8", true)) {
        generateM3u8(
            source = extractorName,
            streamUrl = link,
            referer = referer,
            headers = headers
        ).forEach(callback)
    } else {
        callback.invoke(
            newExtractorLink(
                source = extractorName,
                name = extractorName,
                url = link,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = getQualityFromName(link).takeIf {
                    it != Qualities.Unknown.value
                } ?: qualityFromUrl(link)
            }
        )
    }
}

private fun extractStreamUrls(text: String): List<String> {
    val urls = linkedSetOf<String>()

    Regex("""https?://[^"'\\\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .map { it.value.cleanEscaped() }
        .forEach { urls.add(it) }

    Regex("""["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { it.cleanEscaped() }
        .forEach { urls.add(it) }

    Regex(""":\s*["']([^"']*(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { it.cleanEscaped() }
        .forEach { urls.add(it) }

    Regex("""https?%3A%2F%2F[^"'\\\s<>]+?\.(?:m3u8|mp4)[^"'\\\s<>]*""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .map {
            runCatching {
                URLDecoder.decode(it.value, "UTF-8")
            }.getOrDefault(it.value)
        }
        .map { it.cleanEscaped() }
        .forEach { urls.add(it) }

    Regex("""https?://[^"'\\\s<>]+/(?:stream-vid|media/files)/[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .map { it.value.cleanEscaped() }
        .forEach { urls.add(it) }

    return urls.toList()
}

private fun normalizeUrl(
    url: String,
    baseUrl: String
): String {
    val clean = url.cleanEscaped()

    return when {
        clean.startsWith("http", true) -> clean
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("/") -> {
            val origin = Regex("""^https?://[^/]+""")
                .find(baseUrl)
                ?.value
                ?: ""
            "$origin$clean"
        }
        else -> runCatching {
            URI(baseUrl).resolve(clean).toString()
        }.getOrDefault(clean)
    }
}

private fun isLikelyMediaUrl(url: String): Boolean {
    val value = url.lowercase()
    return value.contains(".m3u8") ||
        value.contains(".mp4") ||
        value.contains("/stream-vid/") ||
        value.contains("/media/files/") ||
        value.contains("stotagehidori.my.id") ||
        value.contains("ayokunjungi.hidoristream")
}

private fun qualityFromUrl(url: String): Int {
    return when {
        url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
        url.contains("1080", true) -> Qualities.P1080.value
        url.contains("720", true) -> Qualities.P720.value
        url.contains("480", true) -> Qualities.P480.value
        url.contains("360", true) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}

private fun String.cleanEscaped(): String {
    return this
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
        .replace("&#038;", "&")
        .trim()
}
