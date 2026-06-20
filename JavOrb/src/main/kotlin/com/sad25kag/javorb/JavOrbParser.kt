package com.sad25kag.javorb

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object JavOrbParser {
    private val cardSelector = listOf(
        ".video-card",
        ".video-item",
        ".thumb-block",
        ".item",
        ".card",
        "article",
        "li:has(a[href*='/video/'])",
        "div:has(> a[href*='/video/']:has(img))"
    ).joinToString(",")

    fun parseListing(document: Document, baseUrl: String): List<JavOrbVideoCard> {
        val results = linkedMapOf<String, JavOrbVideoCard>()

        document.select(cardSelector).forEach { element ->
            parseVideoCard(element, baseUrl)?.let { results[it.url] = it }
        }

        if (results.size < 6) {
            document.select("a[href*='/video/']").forEach { anchor ->
                parseVideoCard(anchor, baseUrl)?.let { results[it.url] = it }
            }
        }

        return results.values.toList()
    }

    fun parseVideoCard(element: Element, baseUrl: String): JavOrbVideoCard? {
        val anchor = if (element.`is`("a[href]")) {
            element
        } else {
            element.selectFirst("h1 a[href], h2 a[href], h3 a[href], .title a[href], a[href*='/video/']")
        } ?: return null

        val url = JavOrbUtils.normalizeUrl(anchor.attr("href"), baseUrl) ?: return null
        if (!JavOrbUtils.isContentUrl(url)) return null

        val container = anchor.bestContainer()
        val image = container.selectFirst("img[data-src], img[data-original], img[data-lazy-src], img[src]:not([src^='data:'])")
            ?: anchor.selectFirst("img[data-src], img[data-original], img[data-lazy-src], img[src]:not([src^='data:'])")

        val title = listOf(
            container.selectFirst("h1, h2, h3, .title, .video-title, .entry-title")?.text(),
            anchor.attr("title"),
            anchor.attr("aria-label"),
            image?.attr("alt"),
            anchor.text(),
            titleFromUrl(url)
        ).firstOrNull { isUsefulTitle(it) }?.let { cleanTitle(it) } ?: return null

        val poster = image?.imageUrl(baseUrl) ?: container.styleImage(baseUrl)
        return JavOrbVideoCard(title = title, url = url, posterUrl = poster)
    }

    fun parseDetail(document: Document, pageUrl: String): JavOrbVideoDetail? {
        val pageText = JavOrbUtils.cleanText(document.text())
        val rawTitle = document.selectFirst("h1, h1.video-title, .video-title h1, .video-title, .entry-title, meta[property='og:title'], title")
            ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
        val title = cleanTitle(rawTitle).ifBlank { titleFromUrl(pageUrl) }
        if (title.isBlank()) return null

        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { JavOrbUtils.normalizeUrl(it, pageUrl) }
            ?: document.selectFirst(".poster img, .video-poster img, .cover img, .thumb img, article img, img[itemprop=image]")?.imageUrl(pageUrl)

        val description = document.selectFirst("meta[property='og:description'], meta[name=description], .description, .synopsis, .desc, .video-description, .entry-content p, article p")
            ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
            ?.let { JavOrbUtils.cleanText(it) }
            ?.takeIf { it.length > 20 }

        val actors = document.select(".actors a, .actor a, a[href*='/actor/'], a[href*='/actors/'], a[href*='/idol/']")
            .map { JavOrbUtils.cleanText(it.text()) }
            .filter { it.length in 2..80 }
            .distinct()
            .take(30)

        val tags = document.select(".category a, .categories a, .genre a, .genres a, a[href*='/category/'], a[href*='/categories/'], a[href*='/types/'], a[href*='/videos/jav-']")
            .map { JavOrbUtils.cleanText(it.text()) }
            .filter { it.length in 2..60 }
            .distinct()
            .take(20)

        val dvdId = listOf(
            document.selectFirst("[data-dvd-id], .dvd-id, .code, .video-code")?.text(),
            title,
            pageText
        ).firstNotNullOfOrNull { JavOrbUtils.dvdId(it) }

        val duration = listOf(
            document.selectFirst("[data-length], .length, .duration, .runtime")?.text(),
            pageText
        ).firstNotNullOfOrNull { JavOrbUtils.firstDurationMinutes(it) }

        val year = listOf(
            document.selectFirst("[data-release], .release, .released, .date, time[datetime]")?.text(),
            document.selectFirst("time[datetime]")?.attr("datetime"),
            title,
            pageText
        ).firstNotNullOfOrNull { JavOrbUtils.firstYear(it) }

        return JavOrbVideoDetail(
            title = title,
            posterUrl = poster,
            description = description,
            dvdId = dvdId,
            duration = duration,
            year = year,
            actors = actors,
            tags = tags
        )
    }

    private fun Element.bestContainer(): Element {
        var current = this
        repeat(5) {
            val parent = current.parent() ?: return current
            val hasContentHref = parent.select("a[href*='/video/']").isNotEmpty()
            val hasImage = parent.select("img").isNotEmpty()
            val hasTitle = parent.select("h1, h2, h3, .title, .video-title, .entry-title").isNotEmpty()
            if (hasContentHref && (hasImage || hasTitle)) current = parent else return current
        }
        return current
    }

    private fun Element.imageUrl(baseUrl: String): String? {
        val candidates = listOf(
            attr("data-src"),
            attr("data-original"),
            attr("data-lazy-src"),
            attr("src").takeUnless { it.startsWith("data:") },
            attr("srcset").split(" ").firstOrNull()
        )
        return candidates.firstOrNull { !it.isNullOrBlank() }?.let { JavOrbUtils.normalizeUrl(it, baseUrl) }
    }

    private fun Element.styleImage(baseUrl: String): String? {
        return Regex("""url\((['\"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
            .find(attr("style"))
            ?.groupValues
            ?.getOrNull(2)
            ?.let { JavOrbUtils.normalizeUrl(it, baseUrl) }
    }

    private fun cleanTitle(value: String?): String {
        return JavOrbUtils.cleanText(value)
            .replace(Regex("(?i)^nonton\\s+"), "")
            .replace(Regex("(?i)^streaming\\s+"), "")
            .replace(Regex("(?i)\\s+-\\s+JAVORB.*$"), "")
            .replace(Regex("(?i)\\s+JAV\\s+Subtitle\\s+Indonesia.*$"), "")
            .trim()
    }

    private fun isUsefulTitle(value: String?): Boolean {
        val text = JavOrbUtils.cleanText(value)
        if (text.length < 3) return false
        val lower = text.lowercase()
        return lower !in setOf("view all", "play", "tonton", "watch", "home", "videos") && !lower.startsWith("http")
    }

    private fun titleFromUrl(url: String): String {
        return url.substringBeforeLast('?')
            .trimEnd('/')
            .substringAfterLast('/')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
