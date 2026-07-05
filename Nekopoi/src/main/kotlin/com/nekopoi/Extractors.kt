package com.nekopoi

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

open class ZippyShare : ExtractorApi() {
    override val name = "ZippyShare"
    override val mainUrl = "https://zippyshare.day"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer).document
        val video = res.selectFirst("a#download-url")?.attr("href") ?: return
        callback.invoke(
            newExtractorLink(name, name, video, ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
            }
        )
    }
}

open class Playmogo : ExtractorApi() {
    override val name = "Playmogo"
    override val mainUrl = "https://playmogo.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer ?: mainUrl)
        val html = response.text
        val passPath = Regex("""['\"](/pass_md5/[^'\"]+)['\"]""").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""(/pass_md5/[A-Za-z0-9_./-]+)""").find(html)?.groupValues?.getOrNull(1)
            ?: return
        val token = passPath.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() } ?: return
        val base = app.get("$mainUrl$passPath", referer = url).text.trim().trim('"', '\'', ' ', '\n', '\r', '\t')
            .takeIf { it.startsWith("http", true) } ?: return
        val finalUrl = base + randomSuffix() + "?token=$token&expiry=${System.currentTimeMillis()}"
        val quality = getQualityFromName(response.document.selectFirst("title")?.text())
        callback.invoke(
            newExtractorLink(name, name, finalUrl, ExtractorLinkType.VIDEO) {
                this.referer = url
                this.quality = quality
            }
        )
    }

    private fun randomSuffix(): String = "NekoPoiCS3"
}

open class Streampoi : ExtractorApi() {
    override val name = "Streampoi"
    override val mainUrl = "https://streampoi.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer ?: mainUrl)
        val html = response.text
        val texts = buildList {
            add(html.cleanEscaped())
            runCatching {
                if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html)?.cleanEscaped() else null
            }.getOrNull()?.let { add(it) }
        }
        val m3u8 = texts.asSequence()
            .flatMap { m3u8Regex.findAll(it).map { match -> match.groupValues[1] } }
            .map { it.cleanEscaped() }
            .firstOrNull { it.contains(".m3u8", ignoreCase = true) }
            ?: return
        M3u8Helper.generateM3u8(name, m3u8, url).forEach(callback)
    }

    private val m3u8Regex = Regex("""(https?://[^'\"\s<>]+\.m3u8[^'\"\s<>]*)""", RegexOption.IGNORE_CASE)
}

private fun String.cleanEscaped(): String {
    return replace("\\/", "/")
        .replace("&amp;", "&")
        .replace("\\u0026", "&")
}
