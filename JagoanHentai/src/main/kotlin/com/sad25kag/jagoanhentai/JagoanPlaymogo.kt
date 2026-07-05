package com.sad25kag.jagoanhentai

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlin.random.Random

open class JagoanPlaymogo : ExtractorApi() {
    override val name = "Playmogo"
    override val mainUrl = "https://playmogo.com"
    override val requiresReferer = true

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.7,en;q=0.5",
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val pageReferer = referer ?: "https://jagoanhentai.fun/"
        val response = app.get(
            url,
            headers = browserHeaders + mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer" to pageReferer,
            ),
            referer = pageReferer,
        )
        val html = response.text.cleanEscaped()
        val passPath = Regex("""['"](/pass_md5/[^'"]+)['"]""").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""(/pass_md5/[A-Za-z0-9_./-]+)""").find(html)?.groupValues?.getOrNull(1)
            ?: return

        val token = passPath.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() } ?: return
        val base = app.get(
            "$mainUrl$passPath",
            headers = browserHeaders + mapOf(
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url,
            ),
            referer = url,
        ).text
            .trim()
            .trim('"', '\'', ' ', '\n', '\r', '\t')
            .cleanEscaped()
            .takeIf { it.startsWith("http", ignoreCase = true) }
            ?: return

        val finalUrl = base + randomSuffix() + "?token=$token&expiry=${System.currentTimeMillis()}"
        val sourceName = "JagoanHentai - $name"
        val quality = getQualityFromName(response.document.selectFirst("title")?.text())
        val mediaHeaders = browserHeaders + mapOf(
            "Accept" to "*/*",
            "Referer" to "$mainUrl/",
        )

        callback.invoke(
            newExtractorLink(sourceName, sourceName, finalUrl, ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
                this.quality = quality
                this.headers = mediaHeaders
            },
        )
    }

    private fun randomSuffix(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return buildString {
            repeat(10) {
                append(chars[Random.nextInt(chars.length)])
            }
        }
    }
}

private fun String.cleanEscaped(): String = replace("\\/", "/")
    .replace("&amp;", "&")
    .replace("\\u0026", "&")
