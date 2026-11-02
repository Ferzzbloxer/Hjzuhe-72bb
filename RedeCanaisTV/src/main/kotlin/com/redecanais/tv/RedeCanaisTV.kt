package com.redecanais.tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder

class RedeCanaisTV : MainAPI() {

    override var name = "RedeCanais TV"
    override var mainUrl = "https://redecanaistv.in"
    override val supportedTypes = setOf(TvType.Live)

    override val hasQuickSearch = true
    override var lang = "pt"

    override val mainPage = mainPageOf("$mainUrl/canais/" to "Canais")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, referer = "$mainUrl/").document
        val items = doc.select("div.items article.item, div.items div.item").mapNotNull { el ->
            val href = el.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
            val title = el.selectFirst("h3, h4, .title")?.text()?.trim() ?: return@mapNotNull null
            newLiveSearchResponse(title, fixUrl(href), TvType.Live) {
                posterUrl = el.selectFirst("img")?.attr("src")
            }
        }
        return newHomePageResponse(request, items)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        app.get(
            "$mainUrl/search/${URLEncoder.encode(query, "UTF-8")}/",
            referer = "$mainUrl/"
        ).document.select("div.items article.item, div.items div.item").mapNotNull { el ->
            val href = el.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
            val title = el.selectFirst("h3, h4, .title")?.text()?.trim() ?: return@mapNotNull null
            newLiveSearchResponse(title, fixUrl(href), TvType.Live)
        }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(fixUrl(url), referer = "$mainUrl/").document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        return newLiveStreamLoadResponse(title, fixUrl(url), fixUrl(url)) {
            posterUrl = doc.selectFirst("div.poster img, meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("div.poster img")?.attr("src")
            plot = doc.selectFirst("#info .wp-content")?.text()?.trim()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = fixUrl(data)
        val doc = app.get(pageUrl, referer = "$mainUrl/").document

        val iframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORECASE)
            .find(doc.html())?.groupValues?.get(1) ?: return false

        return loadExtractor(fixUrl(iframe), pageUrl, subtitleCallback, callback)
    }
}