package com.IStreamFlare

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

class Istreamjam : Istreamcdn() {
    override val mainUrl = base64Decode("aHR0cHM6Ly9zdHJlYW0uaXN0cmVhbWphbS5jb20=")
    override val requiresReferer = false
}

class Neuroflare : Istreamcdn() {
    override val mainUrl = "https://stream.neuroflare.de"
    override val requiresReferer = false
}

class Iasbase : Istreamcdn() {
    override val mainUrl = "https://iasbase.net"
    override val requiresReferer = false
}

private const val CDN_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, lik\u0435 Gecko) Chrome/120.0.0.0 Safari/537.36"

open class Istreamcdn : ExtractorApi() {

    override val name            = "IStreamCDN"
    override val mainUrl         = "https://istreamcdn.com"
    override val requiresReferer = false

    private suspend fun resolveCdnRedirect(phpUrl: String, referer: String): String? =
        withContext(Dispatchers.IO) {
            var conn: java.net.HttpURLConnection? = null
            try {
                conn = URL(phpUrl).openConnection() as java.net.HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout   = 15_000
                conn.setRequestProperty("User-Agent",      CDN_UA)
                conn.setRequestProperty("Referer",         referer)
                conn.setRequestProperty("Accept-Encoding", "gzip")

                conn.connect()

                val code = conn.responseCode

                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    location?.takeIf { it.startsWith("http") }
                } else {
                    try {
                    } catch (_: Exception) {}
                    null
                }
            } catch (_: Exception) {
                null
            } finally {
                conn?.disconnect()
            }
        }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val parsedUrl   = URL(url)
        val host        = "${parsedUrl.protocol}://${parsedUrl.host}"
        val qualityName = referer?.substringBefore("+") ?: ""

        try {
            val streamUrl = resolveCdnRedirect(url, host) ?: run {
                Log.e("IStreamCDN", "No redirect Location from $url")
                return
            }

            if (streamUrl.contains("sub_expire", ignoreCase = true)) return

            val type = when {
                streamUrl.contains(".mpd",  ignoreCase = true) -> ExtractorLinkType.DASH
                streamUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                else -> INFER_TYPE
            }

            callback.invoke(
                newExtractorLink(
                    name   = name,
                    source = name,
                    url    = streamUrl,
                    type   = type
                ) {
                    this.referer = host
                    this.quality = getQualityFromName(qualityName)
                    this.headers = mapOf(
                        "User-Agent" to CDN_UA,
                        "Referer"    to host
                    )
                }
            )

        } catch (e: Throwable) {
            Log.e("IStreamCDN", "getUrl failed", e)
        }
    }
}
