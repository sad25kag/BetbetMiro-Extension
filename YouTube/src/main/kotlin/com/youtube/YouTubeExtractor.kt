package com.youtube

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo

object YouTubeExtractor {
    private val service = ServiceList.YouTube

    private val youtubeHeaders = mapOf(
        "User-Agent" to YouTubeUtils.USER_AGENT,
        "Accept" to "*/*",
        "Origin" to YouTubeSeeds.MAIN_URL,
        "Referer" to "${YouTubeSeeds.MAIN_URL}/"
    )

    private data class DirectYouTubeStream(
        val url: String,
        val resolution: String
    )

    suspend fun loadLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = normalizeWatchUrl(data)
        var found = false

        val info = try {
            StreamInfo.getInfo(service, watchUrl)
        } catch (_: Throwable) {
            null
        }

        if (info != null) {
            info.hlsUrl?.takeIf { it.isNotBlank() }?.let { hls ->
                callback(
                    newExtractorLink("YouTube", "YouTube HLS", hls) {
                        referer = "${YouTubeSeeds.MAIN_URL}/"
                        quality = Qualities.Unknown.value
                        headers = youtubeHeaders
                    }
                )
                found = true
            }

            info.videoStreams
                .orEmpty()
                .mapNotNull { stream ->
                    val streamUrl = stream.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    DirectYouTubeStream(
                        url = streamUrl,
                        resolution = stream.resolution.orEmpty()
                    )
                }
                .distinctBy { it.url }
                .sortedByDescending { YouTubeUtils.qualityFromResolution(it.resolution) }
                .forEach { stream ->
                    val quality = YouTubeUtils.qualityFromResolution(stream.resolution)
                    val label = stream.resolution.takeIf { it.isNotBlank() } ?: "Video"
                    callback(
                        newExtractorLink("YouTube", "YouTube $label", stream.url) {
                            referer = "${YouTubeSeeds.MAIN_URL}/"
                            this.quality = quality
                            headers = youtubeHeaders
                        }
                    )
                    found = true
                }
        }

        if (!found) {
            val candidates = linkedSetOf(watchUrl)
            extractVideoId(watchUrl)?.let { videoId ->
                candidates.add("${YouTubeSeeds.MAIN_URL}/watch?v=$videoId")
                candidates.add("${YouTubeSeeds.MAIN_URL}/embed/$videoId")
                candidates.add("https://youtu.be/$videoId")
            }
            candidates.forEach { url ->
                try {
                    loadExtractor(url, "${YouTubeSeeds.MAIN_URL}/", subtitleCallback) { link ->
                        found = true
                        callback(link)
                    }
                } catch (_: Throwable) {
                    // Keep trying the next canonical YouTube shape.
                }
            }
        }

        return found
    }

    private fun normalizeWatchUrl(url: String): String {
        val trimmed = url.trim()
        val videoId = extractVideoId(trimmed)
        return if (videoId != null) {
            "${YouTubeSeeds.MAIN_URL}/watch?v=$videoId"
        } else {
            trimmed
        }
    }

    private fun extractVideoId(url: String): String? {
        return Regex("""(?:v=|youtu\.be/|shorts/|live/|embed/)([A-Za-z0-9_-]{11})""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
    }
}
