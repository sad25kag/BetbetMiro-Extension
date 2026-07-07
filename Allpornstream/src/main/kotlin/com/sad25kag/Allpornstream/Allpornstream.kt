package com.sad25kag.Allpornstream

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Allpornstream : MainAPI() {

    override var mainUrl = "https://allpornstream.com"
    override var name = "Allpornstream"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override var lang = "id"

    override val supportedTypes = setOf(TvType.NSFW)

    override val vpnStatus = VPNStatus.MightBeNeeded

    private val appHeaders = mapOf(
        "RSC" to "1",
        "Accept" to "*/*",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:149.0) Gecko/20100101 Firefox/149.0"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/categories/amateur" to "Amateur",
        "${mainUrl}/categories/pov" to "POV",
        "${mainUrl}/categories/casting" to "Casting",
        "${mainUrl}/categories/milf" to "MILF",
        "${mainUrl}/categories/old-and-young" to "Old And Young",
        "${mainUrl}/categories/teen" to "Teen",
        "${mainUrl}/categories/lesbian" to "Lesbian",
        "${mainUrl}/categories/girl-on-girl" to "Girl On Girl",
        "${mainUrl}/categories/bisexual" to "Bisexual",
        "${mainUrl}/categories/anal" to "Anal",
        "${mainUrl}/categories/blowjob" to "Blowjob",
        "${mainUrl}/categories/deepthroat" to "Deepthroat",
        "${mainUrl}/categories/handjob" to "Handjob",
        "${mainUrl}/categories/pussy-licking" to "Pussy Licking",
        "${mainUrl}/categories/cumshot" to "Cumshot",
        "${mainUrl}/categories/creampie" to "Creampie",
        "${mainUrl}/categories/squirt" to "Squirt",
        "${mainUrl}/categories/orgasm" to "Orgasm",
        "${mainUrl}/categories/masturbation" to "Masturbation",
        "${mainUrl}/categories/big-tits" to "Big Tits",
        "${mainUrl}/categories/big-ass" to "Big Ass",
        "${mainUrl}/categories/big-dick" to "Big Dick",
        "${mainUrl}/categories/asian" to "Asian",
        "${mainUrl}/categories/latina" to "Latina",
        "${mainUrl}/categories/ebony" to "Ebony"
    )

    private fun posteriduzenle(url: String): String {
        return if (url.startsWith("http")) {
            val encodedurl = URLEncoder.encode(
                url.replace("\\", ""),
                "utf-8"
            )

            "${mainUrl}/api/images?src=$encodedurl&width=384&quality=60"
        } else {
            fixUrl(url)
        }
    }

    private fun nextiparseet(html: String): List<SearchResponse> {
        val document = Jsoup.parse(html)

        return document.select("[data-href], a[href*="/post/"]")
            .mapNotNull { element: Element ->
                val href = element.attr("data-href")
                    .ifBlank { element.attr("href") }

                val url = fixUrl(href)

                if (!url.contains("/post/")) return@mapNotNull null

                val title = element.attr("data-title")
                    .ifBlank { element.attr("title") }
                    .ifBlank {
                        element.selectFirst("h2, h3, .title")
                            ?.text()
                            .orEmpty()
                    }
                    .ifBlank { element.text() }
                    .trim()

                if (title.isBlank()) return@mapNotNull null

                val poster = element.selectFirst("img")
                    ?.let {
                        it.attr("data-images")
                            .ifBlank { it.attr("data-src") }
                            .ifBlank { it.attr("src") }
                    }

                newMovieSearchResponse(
                    title,
                    url,
                    TvType.Movie
                ) {
                    this.posterUrl = poster?.let { posteriduzenle(it) }
                }
            }
    }

}