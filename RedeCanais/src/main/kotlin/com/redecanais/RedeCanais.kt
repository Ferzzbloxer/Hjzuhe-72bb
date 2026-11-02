package com.redecanais

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class RedeCanais : MainAPI() {

    override var name = "RedeCanais"

    override var mainUrl: String
        get() = Cfg.domain
        set(value) { Cfg.domain = value }

    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "pt"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private var warned = false

    override val mainPage: List<MainPageData>
        get() {
            val b = mainUrl.ifBlank { return emptyList() }
            return mainPageOf(
                "$b/browse-filmes-videos-1-date.html" to "Filmes Recentes",
                "$b/browse-series-videos-1-date.html" to "Séries Recentes",
                "$b/browse-animes-videos-1-date.html" to "Animes",
                "$b/browse-desenhos-videos-1-date.html" to "Desenhos"
            )
        }

    /* ------------------------- configuração ------------------------- */

    private fun toast(msg: String) {
        val ctx = Cfg.ctx ?: return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun requireDomain(): String? {
        if (Cfg.isConfigured) return mainUrl
        if (!warned) {
            warned = true
            toast("Informe o endereço do RedeCanais no campo de busca (ex.: https://redecanais.by)")
        }
        return null
    }

    private suspend fun maybeSaveDomain(query: String): Boolean {
        val candidate = Cfg.normalize(query)
        val looksUrl = query.startsWith("http", true) ||
            (query.contains(".") && !query.contains(" ") && query.contains("redecanais", true))
        if (!looksUrl || candidate.isBlank()) return false

        return try {
            val res = Cf.get("$candidate/")
            val body = res.text.take(20_000)
            if (res.code in 200..399 && body.contains("redecanais", true)) {
                Cfg.domain = candidate
                Cf.reset()
                warned = false
                toast("Endereço salvo: $candidate")
                true
            } else {
                toast("$candidate respondeu ${res.code} e não parece o RedeCanais")
                false
            }
        } catch (t: Throwable) {
            toast("Não foi possível acessar $candidate")
            false
        }
    }

    /* ------------------------- parsing ------------------------- */

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = selectFirst("a[href]") ?: return null
        val href = aTag.attr("href")
        if (!href.contains(".html") && !href.contains("_")) return null

        val img = selectFirst("img")
        val title = img?.attr("alt")?.trim()
            ?: selectFirst(".pm-video-title, h3, h4, .title")?.text()?.trim()
            ?: return null

        val poster = img?.let { it.attr("data-echo").ifBlank { it.attr("data-src").ifBlank { it.attr("src") } } }
        val isSeries = title.contains("temporada", true) || href.contains("serie", true)
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, fixUrl(href), type) {
            this.posterUrl = poster
            this.year = Regex("""(\d{4})""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    /* ------------------------- catálogo ------------------------- */

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = requireDomain() ?: return newHomePageResponse(request, emptyList())
        val url = if (page == 1) request.data else request.data.replace("-1-", "-$page-")
        val doc = Cf.get(url, referer = "$base/").document
        val items = doc.select("li.pm-video-item, div.pm-video-thumb, div.video-thumb, li[class*='pm-li-video']")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (maybeSaveDomain(query)) return emptyList()
        val base = requireDomain() ?: return emptyList()
        val searchUrl = "$base/search.php?keywords=${URLEncoder.encode(query, "UTF-8")}&video-id="
        val doc = Cf.get(searchUrl, referer = "$base/").document
        return doc.select("li.pm-video-item, div.pm-video-thumb, div.video-thumb, li[class*='pm-li-video']")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val base = requireDomain() ?: return null
        val pageUrl = fixUrl(url)
        val doc = Cf.get(pageUrl, referer = "$base/").document

        val title = doc.selectFirst("h1, .pm-video-heading h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: return null

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("#video-wrapper img, .pm-video-thumb img")?.attr("src")

        val plot = doc.selectFirst("#pm-details, .pm-video-description, #info")?.text()?.trim()
        val year = Regex("""\d{4}""").find(title)?.value?.toIntOrNull()

        // Check if page contains multiple episode links (Series) or is a single Movie
        val epLinks = doc.select("a[href*='_'][href$='.html']").filter { el ->
            val text = el.text()
            text.contains("Episódio", true) || text.contains("Ep.", true) || text.contains("Temp", true)
        }

        if (epLinks.isNotEmpty()) {
            val episodes = epLinks.mapIndexed { idx, el ->
                val epTitle = el.text().trim()
                val epNum = Regex("""(?i)(?:epis[oó]dio|ep\.?)\s*(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
                newEpisode(fixUrl(el.attr("href"))) {
                    this.name = epTitle
                    this.episode = epNum
                }
            }
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        // Movie
        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    /* ------------------------- players & extraction ------------------------- */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val base = requireDomain() ?: return false
        val pageUrl = fixUrl(data)
        val doc = Cf.get(pageUrl, referer = "$base/").document

        var found = false

        // 1. Locate player iframe in page
        val iframes = doc.select("#video-wrapper iframe, iframe[name='player_iframe'], iframe[src*='player'], iframe[src*='server'], iframe[src]")
            .mapNotNull { it.attr("src").takeIf { src -> src.isNotBlank() } }
            .filterNot { it.contains("facebook") || it.contains("twitter") || it.contains("disqus") }

        iframes.forEach { rawIframe ->
            val iframeUrl = fixUrl(rawIframe)

            // Direct video link inside iframe URL
            if (iframeUrl.contains(".mp4") || iframeUrl.contains(".m3u8")) {
                emitLink(iframeUrl, pageUrl, callback)
                found = true
                return@forEach
            }

            // Fetch iframe page to extract actual video stream
            val playerDoc = runCatching { Cf.get(iframeUrl, referer = pageUrl) }.getOrNull()
            if (playerDoc != null) {
                val html = playerDoc.text

                // Try <video> and <source> tags
                val videoSources = playerDoc.document.select("video source[src], #instructions source[src], video[src], source[src]")
                    .mapNotNull { it.attr("src").takeIf { s -> s.isNotBlank() } }

                videoSources.forEach { vSrc ->
                    val streamUrl = fixUrl(vSrc)
                    emitLink(streamUrl, iframeUrl, callback)
                    found = true
                }

                // Regex search for direct streams inside scripts (mp4/m3u8)
                val scriptMatches = Regex("""(?:file|src)\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
                    .findAll(html)
                    .map { it.groupValues[1] }
                    .toList()

                scriptMatches.forEach { sUrl ->
                    emitLink(fixUrl(sUrl), iframeUrl, callback)
                    found = true
                }

                // Nested iframe within player page
                val nestedIframe = playerDoc.document.selectFirst("iframe[src]")?.attr("src")
                if (!nestedIframe.isNullOrBlank()) {
                    loadExtractor(fixUrl(nestedIframe), iframeUrl, subtitleCallback, callback)
                    found = true
                }
            }

            // Fallback to standard CloudStream extractors
            loadExtractor(iframeUrl, pageUrl, subtitleCallback, callback)
        }

        return found
    }

    private fun emitLink(streamUrl: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val isM3u8 = streamUrl.contains(".m3u8", true)
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "RedeCanais",
                url = streamUrl,
                referer = referer,
                quality = Qualities.P1080.value,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            )
        )
    }
}