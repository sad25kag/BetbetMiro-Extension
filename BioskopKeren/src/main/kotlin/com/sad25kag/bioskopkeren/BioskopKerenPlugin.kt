package com.sad25kag.bioskopkeren

import android.content.Context
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@CloudstreamPlugin
class BioskopKerenPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BioskopKeren())
        registerExtractorAPI(BioskopKerenVidHide())
    }
}

class BioskopKerenVidHide : ExtractorApi() {
    override val name = "VidHide"
    override val mainUrl = "https://vidhide.org"
    override val requiresReferer = true

    private val sourceReferer = "http://134.209.20.140/"
    private val apiOrigin = "https://vidhide.org"

    private val apiHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/plain, */*; q=0.01",
        "Origin" to apiOrigin
    )

    private val hlsHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Origin" to apiOrigin
    )

    private val delegates = listOf(
        BioskopKerenVidHideCore(),
        BioskopKerenVidHideProCore(),
        BioskopKerenVidHideFilesimCore()
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageReferer = referer ?: sourceReferer
        val candidates = linkedSetOf<String>()
        candidates.add(url)

        val page = runCatching {
            app.get(url, headers = apiHeaders, referer = pageReferer, timeout = 30L)
        }.getOrNull()

        val html = page?.text?.decodeEscaped().orEmpty()
        if (html.isNotBlank()) {
            if (resolveJwPlayerSources(html, url, subtitleCallback, callback)) return

            val document = Jsoup.parse(html, url)

            document.select("#servers a[data-url], a[data-url*='/embed/'], form[action*='/embed/']")
                .mapNotNull { element ->
                    listOf("data-url", "action", "href")
                        .mapNotNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }
                        .firstOrNull()
                }
                .mapNotNull { resolveUrl(it, url) }
                .forEach { candidates.add(it) }

            extractJsVar(html, "downloadURL")
                ?.let { resolveUrl(it, url) }
                ?.let { candidates.add(it) }
        }

        candidates.distinct().forEach { candidate ->
            delegates.forEach { extractor ->
                runCatching {
                    extractor.getUrl(candidate, pageReferer, subtitleCallback, callback)
                }
            }
        }
    }

    private data class JwVars(
        val pd: String,
        val ps: String,
        val qsx: String,
        val kaken: String,
        val apiConfigBase: String
    )

    private suspend fun resolveJwPlayerSources(
        html: String,
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val vars = extractJwVars(html) ?: return false

        val apiConfigJson = fetchAndDecryptApiConfig(vars)
        val apiBase = apiConfigJson
            ?.optString("apiURL")
            ?.takeIf { it.isNotBlank() }
            ?.decodeEscaped()
            ?.trimEnd('/')
            ?: vars.apiConfigBase.substringBefore("/api-config").trimEnd('/').ifBlank { "https://s3.vidhide.org" }

        val apiJson = fetchAndDecryptSourceApi(apiBase, vars)
            ?: fetchAndDecryptSourceApi(vars.apiConfigBase.substringBefore("/api-config").trimEnd('/'), vars)
            ?: return false

        return emitSources(apiJson, iframeUrl, subtitleCallback, callback)
    }

    private fun extractJwVars(html: String): JwVars? {
        val pd = extractJsVar(html, "pd") ?: return null
        val ps = extractJsVar(html, "ps") ?: return null
        val qsx = extractJsVar(html, "qsx") ?: return null
        val kaken = extractJsVar(html, "kaken") ?: return null
        val apiConfigBase = extractJsVar(html, "apx")
            ?.let { safeBase64Decode(it) }
            ?.takeIf { it.startsWith("http", true) }
            ?: "https://s3.vidhide.org/api-config/"

        return JwVars(
            pd = pd,
            ps = ps,
            qsx = qsx,
            kaken = kaken,
            apiConfigBase = apiConfigBase
        )
    }

    private suspend fun fetchAndDecryptApiConfig(vars: JwVars): JSONObject? {
        val apiConfigUrl = vars.apiConfigBase.trimEnd('/') + "/" + vars.qsx + "?p=" + vars.ps + "&_=" + System.currentTimeMillis()
        val encrypted = runCatching {
            app.get(
                apiConfigUrl,
                headers = apiHeaders,
                timeout = 30L
            ).text
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        val decrypted = decryptVidHidePayload(encrypted, vars.pd) ?: return null
        return runCatching { JSONObject(decrypted) }.getOrNull()
    }

    private suspend fun fetchAndDecryptSourceApi(apiBase: String, vars: JwVars): JSONObject? {
        val cleanBase = apiBase.trimEnd('/').ifBlank { "https://s3.vidhide.org" }
        val apiUrl = "$cleanBase/api/?p=${vars.ps}"
        val encrypted = runCatching {
            app.post(
                apiUrl,
                headers = apiHeaders + mapOf("Content-Type" to "text/plain"),
                requestBody = vars.kaken.toRequestBody("text/plain".toMediaType()),
                timeout = 30L
            ).text
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        val decrypted = decryptVidHidePayload(encrypted, vars.pd) ?: return null
        return runCatching { JSONObject(decrypted) }.getOrNull()
    }

    private suspend fun emitSources(
        json: JSONObject,
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sources = json.optJSONArray("sources") ?: return false
        var found = false

        for (index in 0 until sources.length()) {
            val source = sources.optJSONObject(index) ?: continue
            val file = source.optString("file")
                .decodeEscaped()
                .takeIf { it.isNotBlank() }
                ?: continue
            val type = source.optString("type")
            val label = source.optString("label").ifBlank { "Original" }
            val linkType = if (type.contains("hls", true) || file.contains("/hls/", true) || file.contains(".m3u8", true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name $label",
                    url = file,
                    type = linkType
                ) {
                    this.referer = ""
                    this.quality = getQualityFromName(label).takeIf { it != Qualities.Unknown.value }
                        ?: Qualities.Unknown.value
                    this.headers = hlsHeaders
                }
            )
            found = true
        }

        emitTracks(json, subtitleCallback)

        return found
    }

    private fun emitTracks(json: JSONObject, subtitleCallback: (SubtitleFile) -> Unit) {
        val tracks = json.optJSONArray("tracks") ?: return
        for (index in 0 until tracks.length()) {
            val track = tracks.optJSONObject(index) ?: continue
            val file = track.optString("file")
                .decodeEscaped()
                .takeIf { it.isNotBlank() }
                ?: continue
            val label = track.optString("label").ifBlank { "Subtitle" }
            if (file.endsWith(".vtt", true) || file.endsWith(".srt", true)) {
    @Suppress("DEPRECATION")
                subtitleCallback.invoke(SubtitleFile(label, file))
            }
        }
    }

    private fun decryptVidHidePayload(encrypted: String, password: String): String? {
        return runCatching {
            val cipherBlob = android.util.Base64.decode(encrypted.trim(), android.util.Base64.DEFAULT)
            if (cipherBlob.size <= 32) return@runCatching null

            val salt = cipherBlob.copyOfRange(0, 16)
            val cipherText = cipherBlob.copyOfRange(16, cipherBlob.size)
            val keyIv = pbkdf2HmacSha256(password.toByteArray(Charsets.UTF_8), salt, 10000, 48)
            val key = keyIv.copyOfRange(0, 32)
            val iv = keyIv.copyOfRange(32, 48)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun pbkdf2HmacSha256(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLength: Int
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hashLength = mac.macLength
        val blocks = (keyLength + hashLength - 1) / hashLength
        val output = ByteArray(blocks * hashLength)
        var offset = 0

        for (block in 1..blocks) {
            mac.reset()
            mac.update(salt)
            mac.update(byteArrayOf(
                (block ushr 24).toByte(),
                (block ushr 16).toByte(),
                (block ushr 8).toByte(),
                block.toByte()
            ))

            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(iterations - 1) {
                mac.reset()
                u = mac.doFinal(u)
                for (i in t.indices) {
                    t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
                }
            }

            System.arraycopy(t, 0, output, offset, t.size)
            offset += t.size
        }

        return output.copyOf(keyLength)
    }

    private fun extractJsVar(html: String, key: String): String? {
        return Regex("""(?:window\.)?$key\s*=\s*[\"']([^\"']+)[\"']""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.decodeEscaped()
            ?.takeIf { it.isNotBlank() }
    }

    private fun safeBase64Decode(value: String): String? {
        return runCatching {
            String(android.util.Base64.decode(value.trim(), android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun resolveUrl(raw: String?, base: String): String? {
        val clean = raw
            ?.trim()
            ?.decodeEscaped()
            ?.takeIf { it.isNotBlank() && it != "#" }
            ?: return null

        if (clean.startsWith("javascript", true)) return null

        return runCatching {
            when {
                clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
                clean.startsWith("//") -> "https:$clean"
                clean.startsWith("/") -> {
                    val uri = URI(base)
                    "${uri.scheme ?: "https"}://${uri.host}$clean"
                }
                else -> URI(base).resolve(clean).toString()
            }
        }.getOrNull()
    }

    private fun String.decodeEscaped(): String {
        val cleaned = replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&quot;", "\"")

        return if (cleaned.contains("%3A%2F%2F", true) || cleaned.contains("%3C", true)) {
            runCatching { URLDecoder.decode(cleaned, "UTF-8") }.getOrDefault(cleaned)
        } else {
            cleaned
        }
    }
}

private class BioskopKerenVidHideCore : VidhideExtractor() {
    override var name = "VidHide"
    override var mainUrl = "https://vidhide.org"
}

private class BioskopKerenVidHideProCore : VidHidePro() {
    override var name = "VidHide"
    override var mainUrl = "https://vidhide.org"
    override val requiresReferer = true
}

private class BioskopKerenVidHideFilesimCore : Filesim() {
    override var name = "VidHide"
    override var mainUrl = "https://vidhide.org"
    override val requiresReferer = true
}