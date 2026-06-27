package com.sad25kag.doronime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.net.URLDecoder

class DoronimeDownload : ExtractorApi() {
    override var name = "Doronime Download"
    override var mainUrl = "https://doronime.id/download"
    override val requiresReferer = true

    private val baseUrl = "https://doronime.id"
    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to USER_AGENT
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val downloadUrl = url.cleanEscaped()
        if (!downloadUrl.startsWith(mainUrl, true)) return

        val sourceReferer = referer?.takeIf { it.isNotBlank() } ?: baseUrl
        val quality = findDownloadQuality(downloadUrl, sourceReferer)
        val targetUrl = resolveDoronimeTarget(downloadUrl, sourceReferer) ?: return

        when {
            targetUrl.isGoogleDriveUrl() -> {
                var emitted = false
                runCatching {
                    loadExtractor(targetUrl, downloadUrl, subtitleCallback) { link ->
                        callback(link)
                        emitted = true
                    }
                }

                if (!emitted) {
                    val driveDownloadUrl = targetUrl.toGoogleDriveDownloadUrl()
                    callback(
                        newExtractorLink("Google Drive", "Google Drive", driveDownloadUrl, ExtractorLinkType.VIDEO) {
                            this.referer = downloadUrl
                            this.quality = quality
                            this.headers = headers + mapOf("Referer" to downloadUrl)
                        }
                    )
                }
            }
            targetUrl.contains("acefile.co", true) -> {
                var emitted = tryAcefile(targetUrl, quality, callback, subtitleCallback)
                if (!emitted) {
                    runCatching {
                        loadExtractor(targetUrl, downloadUrl, subtitleCallback) { link ->
                            callback(link)
                            emitted = true
                        }
                    }
                }
            }
        }
    }

    private suspend fun resolveDoronimeTarget(downloadUrl: String, referer: String): String? {
        val downloadResponse = runCatching {
            app.get(
                downloadUrl,
                headers = headers + mapOf("Referer" to referer),
                referer = referer,
                timeout = 20000L
            )
        }.getOrNull() ?: return null

        val document = downloadResponse.document
        val checker = document.selectFirst("#SafelinkChecker[data-url][data-id]") ?: return null
        val endpoint = normalizeUrl(checker.attr("data-url"), downloadUrl) ?: "$baseUrl/safelink"
        val id = checker.attr("data-id").ifBlank {
            Regex("""[?&]id=([^&]+)""").find(downloadUrl)?.groupValues?.getOrNull(1).orEmpty()
        }
        if (id.isBlank()) return null

        val csrf = document.selectFirst("meta[name=csrf-token]")?.attr("content").orEmpty()
        val safelinkResponse = runCatching {
            app.post(
                endpoint,
                headers = headers + mapOf(
                    "Referer" to downloadUrl,
                    "Origin" to baseUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "X-CSRF-TOKEN" to csrf
                ),
                referer = downloadUrl,
                data = mapOf("id" to id),
                timeout = 20000L
            )
        }.getOrNull() ?: return null

        val goUrl = Regex(""""url"\s*:\s*"([^"]+)""", RegexOption.IGNORE_CASE)
            .find(safelinkResponse.text)
            ?.groupValues
            ?.getOrNull(1)
            ?.cleanEscaped()
            ?.let { normalizeUrl(it, endpoint) }
            ?: return null

        return resolveGoTarget(goUrl, downloadUrl)
    }

    private suspend fun resolveGoTarget(goUrl: String, referer: String): String? {
        val response = runCatching {
            app.get(
                goUrl,
                headers = headers + mapOf("Referer" to referer),
                referer = referer,
                allowRedirects = false,
                timeout = 20000L
            )
        }.getOrNull() ?: return null

        val html = response.text.cleanEscaped()
        val candidates = linkedSetOf<String>()

        listOfNotNull(response.headers["location"], response.headers["Location"]).forEach { candidates.add(it) }
        Regex("""url=['\"]?([^'\"<>\s]+)""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1] }
            .forEach { candidates.add(it) }
        Regex("""href=['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1] }
            .forEach { candidates.add(it) }

        return candidates
            .mapNotNull { it.extractExternalTarget() }
            .firstOrNull { it.isGoogleDriveUrl() || it.contains("acefile.co", true) }
    }

    private suspend fun findDownloadQuality(downloadUrl: String, referer: String): Int {
        if (!referer.startsWith(baseUrl, true)) return Qualities.Unknown.value
        val id = Regex("""[?&]id=([^&]+)""").find(downloadUrl)?.groupValues?.getOrNull(1).orEmpty()
        val document = runCatching {
            app.get(referer, headers = headers + mapOf("Referer" to baseUrl), referer = baseUrl).document
        }.getOrNull() ?: return Qualities.Unknown.value

        document.select(".Download__group").forEach { group ->
            val match = group.select("a[href]").any { anchor ->
                val href = normalizeUrl(anchor.attr("href"), referer).orEmpty()
                href == downloadUrl || (id.isNotBlank() && href.contains(id))
            }
            if (match) {
                return getQualityFromName(group.selectFirst(".Download__group-title")?.text().orEmpty())
            }
        }

        return Qualities.Unknown.value
    }

    private suspend fun tryAcefile(
        url: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val cleanUrl = url.cleanEscaped()
        val id = Regex("""acefile\.co/(?:f|file|player)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(cleanUrl)?.groupValues?.getOrNull(1)
            ?: Regex("""[?&](?:id|file)=([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(cleanUrl)?.groupValues?.getOrNull(1)

        val visited = linkedSetOf<String>()
        val pageQueue = mutableListOf<String>()

        fun addPage(page: String, base: String = cleanUrl) {
            val normalized = normalizeUrl(page, base)?.cleanEscaped() ?: return
            if (normalized.isBlank() || normalized == "#") return
            if (normalized.isArchiveDownloadUrl()) return
            if (visited.add(normalized)) pageQueue.add(normalized)
        }

        suspend fun emitAcefileDirect(candidate: String, referer: String): Boolean {
            val fixed = candidate.cleanEscaped().replace(".txt", ".m3u8")
            if ((!fixed.isPlayableMediaUrl() && !fixed.isAcefileServicePlayUrl()) ||
                fixed.isArchiveDownloadUrl() ||
                fixed.isAcefileLandingPageUrl()
            ) return false

            val type = if (fixed.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(
                newExtractorLink("AceFile", "AceFile", fixed, type) {
                    this.referer = referer
                    this.quality = quality
                    this.headers = headers + mapOf("Referer" to referer)
                }
            )
            return true
        }

        if (id != null) addPage("https://acefile.co/player/$id") else addPage(cleanUrl)

        var emitted = false
        var index = 0
        while (index < pageQueue.size && index < 6) {
            val pageUrl = pageQueue[index++]
            val pageReferer = when {
                pageUrl.contains("/player/", true) -> cleanUrl
                pageUrl.contains("/local/", true) -> "https://acefile.co/player/${id.orEmpty()}"
                else -> cleanUrl
            }

            val response = runCatching {
                app.get(
                    pageUrl,
                    headers = headers + mapOf(
                        "Referer" to pageReferer,
                        "Origin" to "https://acefile.co"
                    ),
                    referer = pageReferer,
                    timeout = 20000L
                )
            }.getOrNull() ?: continue

            val document = response.document
            val html = response.text.cleanEscaped()

            extractAcefileSourceUrls(html, pageUrl).forEach { direct ->
                if (emitAcefileDirect(direct, pageUrl)) emitted = true
            }
            if (emitted) return true

            collectPlayableUrls(document, html, pageUrl)
                .filterNot { it.isAcefileLandingPageUrl() }
                .forEach { direct ->
                    if (emitAcefileDirect(direct, pageUrl)) emitted = true
                }
            if (emitted) return true

            extractAcefileLocalPages(document, html, pageUrl).forEach { localPage ->
                addPage(localPage, pageUrl)
            }

            document.select(
                "iframe[src], embed[src], video[src], video source[src], source[src], " +
                    "[data-url], [data-href], [data-link], [data-file], [data-source], [data-video], [data-src]"
            ).forEach { element ->
                listOf("src", "data-url", "data-href", "data-link", "data-file", "data-source", "data-video", "data-src")
                    .map { element.attr(it) }
                    .map { it.cleanEscaped() }
                    .filter { it.isNotBlank() && !it.isAcefileLandingPageUrl() }
                    .forEach { addPage(it, pageUrl) }
            }

            runCatching {
                loadExtractor(pageUrl, pageReferer, subtitleCallback) { link ->
                    val linkUrl = link.url.cleanEscaped()
                    if ((linkUrl.isPlayableMediaUrl() || linkUrl.isAcefileServicePlayUrl()) &&
                        !linkUrl.isAcefileLandingPageUrl() &&
                        !linkUrl.isArchiveDownloadUrl()
                    ) {
                        callback(link)
                        emitted = true
                    }
                }
            }
            if (emitted) return true
        }

        return false
    }

    private fun extractAcefileLocalPages(
        document: org.jsoup.nodes.Document,
        html: String,
        base: String
    ): List<String> {
        val pages = linkedSetOf<String>()
        document.select("[data-holder=local][data-video], [data-video*=local]").forEach { element ->
            listOf("data-video", "data-src", "src", "href")
                .map { element.attr(it) }
                .mapNotNull { normalizeUrl(it, base) }
                .map { it.cleanEscaped() }
                .filter { it.contains("acefile.co/local/", true) }
                .forEach { pages.add(it) }
        }

        Regex("""https?://acefile\.co/local/\d+\?key=[A-Za-z0-9]+""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.value.cleanEscaped() }
            .forEach { pages.add(it) }

        Regex("""/local/(\d+)\?key=([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { "https://acefile.co/local/${it.groupValues[1]}?key=${it.groupValues[2]}" }
            .forEach { pages.add(it) }

        Regex("""'([^']*)'\.split\('\|'\)""")
            .findAll(html)
            .map { it.groupValues[1].split("|") }
            .forEach { parts ->
                val serverIndex = parts.indexOf("server")
                val keyIndex = parts.indexOf("mirrorHasVideo")
                val localId = parts.getOrNull(serverIndex + 1)?.takeIf { it.matches(Regex("""\d{5,}""")) }
                val key = parts.getOrNull(keyIndex + 1)?.takeIf { it.matches(Regex("""[A-Fa-f0-9]{24,}""")) }
                if (localId != null && key != null) {
                    pages.add("https://acefile.co/local/$localId?key=$key")
                }
            }

        return pages.toList()
    }

    private fun extractAcefileSourceUrls(html: String, base: String): List<String> {
        val results = linkedSetOf<String>()
        val encodedSources = linkedSetOf<String>()

        Regex(
            """sources\s*:\s*JSON\.parse\s*\(\s*(?:atob|a2b)\s*\(\s*["']([^"']+)["']\s*\)\s*\)""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .map { it.groupValues[1] }
            .forEach { encodedSources.add(it) }

        Regex(
            """JSON\.parse\s*\(\s*(?:atob|a2b)\s*\(\s*["']([^"']+)["']\s*\)\s*\)""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .map { it.groupValues[1] }
            .forEach { encodedSources.add(it) }

        encodedSources.forEach { encoded ->
            val decoded = decodeAcefileBase64(encoded) ?: return@forEach
            runCatching {
                when (val json = JSONTokener(decoded).nextValue()) {
                    is JSONArray -> {
                        for (i in 0 until json.length()) {
                            json.optJSONObject(i)?.acefileSourceUrl()?.let { normalizeUrl(it, base)?.cleanEscaped()?.let(results::add) }
                        }
                    }
                    is JSONObject -> json.acefileSourceUrl()?.let { normalizeUrl(it, base)?.cleanEscaped()?.let(results::add) }
                }
            }
        }

        return results.toList()
    }

    private fun JSONObject.acefileSourceUrl(): String? {
        return listOf("file", "src", "url", "source")
            .firstNotNullOfOrNull { key -> optString(key).takeIf { it.isNotBlank() } }
    }

    private fun collectPlayableUrls(
        document: org.jsoup.nodes.Document,
        html: String,
        base: String
    ): List<String> {
        val results = linkedSetOf<String>()
        document.select("video[src], video source[src], source[src], a[href]").forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("href") }
            normalizeUrl(raw, base)?.cleanEscaped()
                ?.takeIf { it.isPlayableMediaUrl() && !it.isArchiveDownloadUrl() }
                ?.let(results::add)
        }

        Regex("""https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|mkv|txt)(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.value.cleanEscaped() }
            .filterNot { it.isArchiveDownloadUrl() }
            .forEach { results.add(it) }

        Regex("""https?%3A%2F%2F[^"'\\\s<>]+?(?:%2Em3u8|%2Emp4|%2Ewebm|%2Emkv|%2Etxt)[^"'\\\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value) }
            .map { it.cleanEscaped() }
            .filterNot { it.isArchiveDownloadUrl() }
            .forEach { results.add(it) }

        Regex("""(?i)(?:file|src|source|url|video)\s*[:=]\s*["']([^"']+)["']""")
            .findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .mapNotNull { normalizeUrl(it, base) }
            .map { it.cleanEscaped() }
            .filter { it.isPlayableMediaUrl() && !it.isArchiveDownloadUrl() }
            .forEach { results.add(it) }

        return results.toList()
    }

    private fun decodeAcefileBase64(value: String): String? {
        val clean = value.trim().replace(Regex("""\s+"""), "")
        if (clean.isBlank()) return null
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return runCatching { base64Decode(padded) }.getOrNull()
    }

    private fun normalizeUrl(url: String?, base: String = baseUrl): String? {
        val clean = url?.cleanEscaped().orEmpty()
        if (clean.isBlank() || clean == "#" || clean.startsWith("javascript:", true)) return null
        return when {
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> {
                val origin = runCatching {
                    val uri = URI(base)
                    "${uri.scheme}://${uri.host}"
                }.getOrDefault(baseUrl)
                origin + clean
            }
            else -> runCatching { URI(base).resolve(clean).toString() }
                .getOrElse { clean }
        }
    }

    private fun String.extractExternalTarget(): String? {
        val clean = cleanEscaped()
        val target = Regex("""[?&]url=([^&"'<>\s]+)""", RegexOption.IGNORE_CASE)
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            ?.cleanEscaped()

        return target ?: clean.takeIf { it.isGoogleDriveUrl() || it.contains("acefile.co", true) }
    }

    private fun String.toGoogleDriveDownloadUrl(): String {
        val clean = cleanEscaped()
        val id = Regex("""(?:/d/|[?&]id=)([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?: return clean
        return "https://drive.google.com/uc?export=download&confirm=t&id=$id"
    }

    private fun String.cleanEscaped(): String {
        return trim()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&quot;", "\"")
            .replace("\\\"", "\"")
            .trim('"', '\'', ',', ';')
            .trim()
    }

    private fun String.isGoogleDriveUrl(): Boolean {
        val lower = lowercase()
        return lower.contains("drive.google.com") || lower.contains("docs.google.com")
    }

    private fun String.isPlayableMediaUrl(): Boolean {
        val lower = substringBefore("?").lowercase()
        return lower.endsWith(".m3u8") || lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mkv") || lower.endsWith(".txt")
    }

    private fun String.isArchiveDownloadUrl(): Boolean {
        val lower = substringBefore("?").lowercase()
        return lower.endsWith(".zip") ||
            lower.endsWith(".rar") ||
            lower.endsWith(".7z") ||
            lower.endsWith(".tar") ||
            lower.endsWith(".gz") ||
            lower.contains("-zip") ||
            lower.contains("/zip")
    }

    private fun String.isAcefileLandingPageUrl(): Boolean {
        val lower = lowercase()
        return lower.matches(Regex("""https?://(?:www\.)?acefile\.co/?""")) ||
            lower.matches(Regex("""https?://(?:www\.)?acefile\.co/(?:f|file)/[A-Za-z0-9]+/?""")) ||
            lower.matches(Regex("""https?://(?:www\.)?acefile\.co/player/[A-Za-z0-9]+/?"""))
    }

    private fun String.isAcefileServicePlayUrl(): Boolean {
        val lower = lowercase()
        return lower.contains("acefile.co/local/") ||
            lower.contains("acefile.co/stream/") ||
            lower.contains("acefile.co/download/")
    }
}

class DoronimeOkRu : ExtractorApi() {
    override var name = "OkRu"
    override var mainUrl = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url
            .replace("/video/", "/videoembed/")
            .replace("/videoembed/videoembed/", "/videoembed/")

        val headers = mapOf(
            "Accept" to "*/*",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT,
            "Referer" to embedUrl
        )

        val body = runCatching {
            app.get(embedUrl, headers = headers, referer = referer ?: mainUrl).text.decodePlayerText()
        }.getOrNull().orEmpty()

        val videos = Regex(""""videos"\s*:\s*(\[[^]]+])""")
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::parseVideos)
            .orEmpty()

        videos.forEach { video ->
            val videoUrl = if (video.url.startsWith("//")) "https:${video.url}" else video.url
            val quality = video.name.uppercase()
                .replace("MOBILE", "144p")
                .replace("LOWEST", "240p")
                .replace("LOW", "360p")
                .replace("SD", "480p")
                .replace("HD", "720p")
                .replace("FULL", "1080p")
                .replace("QUAD", "1440p")
                .replace("ULTRA", "2160p")

            callback(
                newExtractorLink(name, name, videoUrl, ExtractorLinkType.VIDEO) {
                    this.referer = embedUrl
                    this.quality = getQualityFromName(quality)
                    this.headers = headers
                }
            )
        }
    }

    private fun parseVideos(value: String): List<OkRuVideo> {
        return runCatching {
            val array = JSONArray(value)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val label = item.optString("name").trim()
                    val url = item.optString("url").trim()
                    if (label.isNotBlank() && url.isNotBlank()) add(OkRuVideo(label, url))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun String.decodePlayerText(): String {
        return replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
    }

    private data class OkRuVideo(val name: String, val url: String)
}
