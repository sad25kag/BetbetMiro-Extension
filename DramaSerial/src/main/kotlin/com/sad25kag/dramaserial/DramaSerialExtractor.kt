package com.sad25kag.dramaserial

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Base64

object DramaSerialExtractor {
    private val candidatePatterns = listOf(
        Regex("""<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""(?:data-src|data-embed|data-video|data-url|data-link|data-file)=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""(?:file|src|source|video|videoUrl|video_url|hls\d*|url|embed|embed_url|embed_frame_url)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""["']((?:https?:)?//[^"']+(?:\.m3u8|\.mp4|\.webm|\.mpd|\.txt)(?:\?[^"']*)?)[""]'""", RegexOption.IGNORE_CASE),
        Regex("""["']((?:/[^"']*)/(?:embed|player|stream|get|watch|video|dl)[^"']*)["']""", RegexOption.IGNORE_CASE),
        Regex("""["']((?:https?:)?//[^"']*(?:gdriveplayer\.(?:io|to|me)|hlsplaylist\.php)[^"']*)["']""", RegexOption.IGNORE_CASE),
        // Broad pattern for known external video hosts — including abysscdn.com
        Regex("""["'](https?://[^"']*(?:minochinos|morencius|pixibay|abyssplayer|abysscdn|dood|streamtape|filemoon|vidhide|vidguard|voe|mixdrop|streamwish|wishfast|mp4upload|uqload|krakenfiles|streamlare|filelions|gdrive|drive\.google)[^"']*)["']""", RegexOption.IGNORE_CASE)
    )

    suspend fun load(pageUrl: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val visited = linkedSetOf<String>()
        val emitted = linkedSetOf<String>()
        var found = false

        fun emit(link: ExtractorLink) {
            val key = link.url.substringBefore("?token=").substringBefore("&token=")
            if (emitted.add(key)) {
                found = true
                callback(link)
            }
        }

        suspend fun resolve(raw: String?, referer: String = pageUrl, depth: Int = 0) {
            val url = raw.absUrlDs(referer) ?: return
            if (url.isNoiseUrlDs()) return
            if (!visited.add(url)) return

            when {
                url.isSubtitleUrlDs() -> {
                    subtitleCallback(newSubtitleFile("Indonesian", url))
                    return
                }
                url.contains("/hlsplaylist.php", ignoreCase = true) && url.isGdrivePlayerUrl() -> {
                    emitGdriveHlsVariants(url, ::emit)
                    return
                }
                url.isVideoUrlDs() -> {
                    emitDirect(url, referer, ::emit)
                    return
                }
                url.contains("minochinos.com/embed/", ignoreCase = true) || url.contains("morencius.com/embed/", ignoreCase = true) -> {
                    extractPackedHost(url, "DramaSerial", ::emit)
                    return
                }
                url.isGdrivePlayerUrl() -> {
                    extractGdrivePlayer(url, referer, ::emit)
                    return
                }
            }

            runCatching {
                loadExtractor(url, referer, subtitleCallback) { link -> emit(link) }
            }

            if (depth >= 3 || isKnownExternal(url)) return

            val response = runCatching {
                app.get(
                    url = url,
                    headers = DramaSerialNetwork.baseHeaders + mapOf("Referer" to referer),
                    referer = referer,
                    timeout = 15000L
                )
            }.getOrNull() ?: return

            val contentType = response.headers["Content-Type"].orEmpty().lowercase()
            val contentLength = response.headers["Content-Length"]?.toLongOrNull()
            if (contentType.startsWith("video/") || contentType.contains("mpegurl") || contentType.contains("dash") || contentType.contains("octet-stream") || (contentLength != null && contentLength > 5_000_000L)) return

            val body = runCatching { response.text.cleanDs() }.getOrNull() ?: return
            for (candidate in collectCandidates(body, url)) {
                runCatching { resolve(candidate, url, depth + 1) }
            }
        }

        val html = DramaSerialNetwork.getText(pageUrl, DramaSerialProvider.DEFAULT_MAIN_URL).cleanDs()
        val document = DramaSerialParser.parseDocumentFromHtml(html, pageUrl)

        document.select(
            ".gmr-embed-responsive iframe[src], iframe[src], " +
                ".muvipro-player-tabs a[href], .gmr-download-wrap a[href], .entry-content a[href], " +
                "a[href*='minochinos.com'], a[href*='morencius.com'], " +
                "a[href*='abysscdn.com'], iframe[src*='abysscdn.com'], " +
                "a[href*='gdriveplayer.io'], a[href*='gdriveplayer.to'], a[href*='gdriveplayer.me'], " +
                "video source[src], source[src], track[src], " +
                "a[href*='.srt'], a[href*='.vtt'], a[href*='.mp4'], a[href*='.m3u8'], a[href*='.webm']"
        ).forEach { element ->
            val candidate = listOf("src", "href", "data-src", "data-embed", "data-video", "data-url", "data-link", "data-file")
                .map { element.attr(it) }
                .firstOrNull { it.isNotBlank() }
            if (candidate != null) runCatching { resolve(candidate, pageUrl, 0) }
        }

        for (candidate in collectCandidates(html, pageUrl)) {
            runCatching { resolve(candidate, pageUrl, 0) }
        }

        return found
    }

    private suspend fun extractPackedHost(iframeUrl: String, sourceName: String, emit: (ExtractorLink) -> Unit) {
        val iframeHtml = runCatching {
            app.get(
                url = iframeUrl,
                headers = DramaSerialNetwork.baseHeaders + mapOf("Referer" to DramaSerialProvider.DEFAULT_MAIN_URL),
                referer = DramaSerialProvider.DEFAULT_MAIN_URL
            ).text.cleanDs()
        }.getOrNull().orEmpty()
        if (iframeHtml.isBlank()) return

        val unpacked = runCatching { getAndUnpack(iframeHtml) }.getOrNull().orEmpty().ifBlank { iframeHtml }
        collectCandidates(unpacked, iframeUrl)
            .filter { it.isVideoUrlDs() }
            .distinct()
            .forEach { videoUrl -> emitDirect(videoUrl, iframeUrl, emit, sourceName) }
    }

    private suspend fun extractGdrivePlayer(playerUrl: String, referer: String, emit: (ExtractorLink) -> Unit) {
        val playerHtml = runCatching {
            app.get(
                url = playerUrl,
                headers = DramaSerialNetwork.baseHeaders + mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to referer
                ),
                referer = referer,
                timeout = 15000L
            ).text.cleanDs()
        }.getOrNull() ?: return

        val unpacked = runCatching { getAndUnpack(playerHtml) }
            .getOrNull()
            .orEmpty()
            .ifBlank { playerHtml }

        val hlsCandidates = linkedSetOf<String>()
        val sources = linkedSetOf(playerHtml, unpacked)
        collectGdriveDecodedScripts(playerHtml).forEach(sources::add)
        collectGdriveDecodedScripts(unpacked).forEach(sources::add)

        sources.filter { it.isNotBlank() }.forEach { source ->
            collectGdriveHlsCandidates(source, playerUrl).forEach(hlsCandidates::add)
            collectCandidates(source, playerUrl)
                .filter { candidate -> candidate.isGdrivePlayerUrl() && candidate.contains("/hlsplaylist.php", ignoreCase = true) }
                .forEach(hlsCandidates::add)
        }

        hlsCandidates
            .filterNot { it.isNoiseUrlDs() }
            .flatMap { it.gdriveHlsVariants() }
            .distinct()
            .forEach { videoUrl -> emitGdriveHls(videoUrl, emit) }
    }

    private fun collectGdriveHlsCandidates(html: String, baseUrl: String): Set<String> {
        val clean = html.cleanDs()
        val out = linkedSetOf<String>()
        val gdriveBase = baseUrl.toGdrivePlayerBaseUrl() ?: "https://gdriveplayer.io"

        Regex("""((?:https?:)?//gdriveplayer\.(?:io|to|me)/hlsplaylist\.php[^\s"'<>),;]+)""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.cleanGdriveCandidate()?.absUrlDs(baseUrl) }
            .forEach(out::add)

        Regex("""(?:^|["'=:(\s])(/hlsplaylist\.php[^\s"'<>),;]+)""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.cleanGdriveCandidate()?.absUrlDs(gdriveBase) }
            .forEach(out::add)

        Regex("""(?i)HLS\s*=\s*["']([^"']*hlsplaylist\.php[^"']+)["']""")
            .findAll(clean)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.cleanGdriveCandidate()?.absUrlDs(gdriveBase) }
            .forEach(out::add)

        val sValue = Regex("""(?i)(?:["']?s["']?\s*[:=]\s*["'])([^"']+)(?:["'])""").find(clean)?.groupValues?.getOrNull(1)
        val idhlsValue = Regex("""(?i)(?:["']?idhls["']?\s*[:=]\s*["'])([^"']+)(?:["'])""").find(clean)?.groupValues?.getOrNull(1)

        if (!sValue.isNullOrBlank() && !idhlsValue.isNullOrBlank()) {
            val idhls = idhlsValue.cleanDs().let { value -> if (value.endsWith(".m3u8", true)) value else "$value.m3u8" }
            out.add("$gdriveBase/hlsplaylist.php?s=${sValue.toGdriveQueryValue()}&idhls=${idhls.toGdriveQueryValue()}")
        }

        return out
    }

    private fun collectGdriveDecodedScripts(html: String): Set<String> {
        val out = linkedSetOf<String>()
        val clean = html.cleanDs()
        Regex("""var\s+k\s*=\s*["']([^"']+)["']\s*,\s*b\s*=\s*atob\(["']([^"']+)["']\)""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .mapNotNull { match -> decodeGdriveXor(match.groupValues.getOrNull(1), match.groupValues.getOrNull(2)) }
            .forEach(out::add)
        return out
    }

    private fun decodeGdriveXor(key: String?, encoded: String?): String? {
        val actualKey = key?.takeIf { it.isNotBlank() } ?: return null
        val raw = encoded?.replace("\\/", "/")?.takeIf { it.isNotBlank() } ?: return null
        val bytes = runCatching { Base64.getDecoder().decode(raw) }.getOrNull() ?: return null
        val builder = StringBuilder(bytes.size)
        bytes.forEachIndexed { index, value ->
            val decoded = (value.toInt() and 0xff) xor actualKey[index % actualKey.length].code
            builder.append(decoded.toChar())
        }
        return builder.toString().cleanDs()
    }

    fun collectCandidates(html: String, baseUrl: String): Set<String> {
        val clean = html.cleanDs()
        val out = linkedSetOf<String>()

        fun collectFrom(text: String, currentBase: String = baseUrl) {
            val normalized = text.cleanDs()
            candidatePatterns.forEach { pattern ->
                pattern.findAll(normalized).forEach { match ->
                    match.groupValues.getOrNull(1)?.absUrlDs(currentBase)?.let(out::add)
                }
            }
        }

        collectFrom(clean)

        Regex("""atob\(["']([^"']+)["']\)""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .mapNotNull { decodeBase64Ds(it.groupValues[1]) }
            .forEach { decoded -> collectFrom(decoded, baseUrl) }

        Regex("""Base64\.decode\(["']([^"']+)["']\)""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .mapNotNull { decodeBase64Ds(it.groupValues[1]) }
            .forEach { decoded -> collectFrom(decoded, baseUrl) }

        if (clean.contains("eval(function(p,a,c,k,e,d)", ignoreCase = true)) {
            runCatching { getAndUnpack(clean) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() && it != clean }
                ?.let { unpacked -> collectFrom(unpacked, baseUrl) }
        }

        return out.filterNot { it.isNoiseUrlDs() }.toCollection(linkedSetOf())
    }

    private suspend fun emitGdriveHlsVariants(url: String, emit: (ExtractorLink) -> Unit) {
        url.gdriveHlsVariants().forEach { videoUrl -> emitGdriveHls(videoUrl, emit) }
    }

    private suspend fun emitGdriveHls(url: String, emit: (ExtractorLink) -> Unit) {
        emit(
            newExtractorLink("DramaSerial", "GdrivePlayer HLS", url, ExtractorLinkType.M3U8) {
                this.referer = ""
                this.quality = url.qualityDs()
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*",
                    "Origin" to "null"
                )
            }
        )
    }

    private suspend fun emitDirect(url: String, referer: String, emit: (ExtractorLink) -> Unit, sourceName: String = "DramaSerial") {
        val type = if (url.contains(".m3u8", true) || url.contains(".txt", true)) ExtractorLinkType.M3U8 else INFER_TYPE
        emit(
            newExtractorLink("DramaSerial", sourceName, url, type) {
                this.referer = referer
                this.quality = url.qualityDs()
                this.headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)
            }
        )
    }

    private fun String.gdriveHlsVariants(): List<String> {
        val clean = cleanDs()
        val variants = linkedSetOf<String>()
        variants.add(clean)
        variants.add(clean.replace(Regex("""^http://gdriveplayer\.(io|to|me)""", RegexOption.IGNORE_CASE), "https://gdriveplayer.$1"))
        variants.add(clean.replace(Regex("""^https://gdriveplayer\.(io|to|me)""", RegexOption.IGNORE_CASE), "http://gdriveplayer.$1"))
        return variants.filter { it.isNotBlank() }
    }

    private fun String.isGdrivePlayerUrl(): Boolean {
        return contains("gdriveplayer.io", ignoreCase = true) ||
            contains("gdriveplayer.to", ignoreCase = true) ||
            contains("gdriveplayer.me", ignoreCase = true)
    }

    private fun String.toGdrivePlayerBaseUrl(): String? {
        return Regex("""(?i)^(https?:)?//(gdriveplayer\.(?:io|to|me))""")
            .find(cleanDs())
            ?.let { match -> "${match.groupValues.getOrNull(1).orEmpty().ifBlank { "https:" }}//${match.groupValues[2]}" }
    }

    private fun String.cleanGdriveCandidate(): String {
        return cleanDs()
            .trim()
            .trimEnd(',', ';', ')')
            .replace("\\/", "/")
    }

    private fun String.toGdriveQueryValue(): String {
        val value = cleanDs()
        return if (value.contains("%")) value else value.urlEncodeDs()
    }

    private fun isKnownExternal(url: String): Boolean {
        val lower = url.lowercase()
        return listOf(
            "dood",
            "streamtape",
            "filemoon",
            "vidhide",
            "vidguard",
            "voe",
            "mixdrop",
            "streamwish",
            "wishfast",
            "mp4upload",
            "uqload",
            "krakenfiles",
            "streamlare",
            "filelions",
            "drive.google",
            "gdrive",
            "ok.ru",
            "streamsb",
            "sbembed",
            "upstream",
            "vidoza",
            "fembed",
            "feurl",
            "gofile",
            "pixeldrain",
            "minochinos",
            "morencius",
            "abysscdn"
        ).any { lower.contains(it) }
    }
}
