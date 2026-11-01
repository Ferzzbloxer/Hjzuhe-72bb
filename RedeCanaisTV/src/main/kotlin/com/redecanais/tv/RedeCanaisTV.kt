package com.redecanais.tv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * TODO: confirmar seletores contra redecanaistv.in — o host é separado do VOD e não
 * acompanha a configuração de domínio. Ajuste BASE e os seletores antes de publicar.
 */
class RedeCanaisTV : LiveAPI() {

    override var name = "RedeCanais TV"
    override var mainUrl = "https://redecanaistv.in"

    override val hasQuickSearch = true
    override var lang = "pt"

    override val mainPage = mainPageOf("$mainUrl/canais/" to "Canais")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = com.redecanais.Cf.get(request.data, referer = "$mainUrl/").document
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
        com.redecanais.Cf.get(
            "$mainUrl/search/${java.net.URLEncoder.encode(query, "UTF-8")}/",
            referer = "$mainUrl/"
        ).document.select("div.items article.item, div.items div.item").mapNotNull { el ->
            val href = el.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
            val title = el.selectFirst("h3, h4, .title")?.text()?.trim() ?: return@mapNotNull null
            newLiveSearchResponse(title, fixUrl(href), TvType.Live)
        }

    override suspend fun load(url: String): LoadResponse? {
        val doc = com.redecanais.Cf.get(fixUrl(url), referer = "$mainUrl/").document
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
        val doc = com.redecanais.Cf.get(pageUrl, referer = "$mainUrl/").document

        val iframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(doc.html())?.groupValues?.get(1) ?: return false

        loadExtractor(fixUrl(iframe), pageUrl, subtitleCallback) { link ->
            callback.invoke(
                newExtractorLink(this.name, link.name, link.url, ExtractorLinkType.M3U8) {
                    this.referer = pageUrl
                }
            )
        }
        return true
    }
}