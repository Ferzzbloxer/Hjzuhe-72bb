package com.redecanais

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
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
        val candidateIframeUrls = mutableSetOf<String>()

        // 1. Locate player iframe from #video-wrapper
        val iframeElements = doc.select(
            "#video-wrapper iframe, iframe[name='Player'], iframe[src*='player3'], iframe[src*='server.php']"
        )

        iframeElements.forEach { el ->
            val src = el.attr("src").trim()
            if (src.isNotBlank()) {
                val fullUrl = fixUrl(src)
                candidateIframeUrls.add(fullUrl)
                // RedeCanais player variant that serves the actual HTML5 video element
                if (fullUrl.contains("server.php")) {
                    candidateIframeUrls.add(fullUrl.replace("server.php", "serverplayerfree.php"))
                    candidateIframeUrls.add(fullUrl.replace("server.php", "serverfplayerfree.php"))
                } else if (fullUrl.contains(".php")) {
                    candidateIframeUrls.add(fullUrl.replace(".php", "playerfree.php"))
                }
            }
        }

        // Also collect alternative server buttons if present
        doc.select("a[href*='server.php'], a[href*='player3'], button[data-src*='server.php']").forEach { el ->
            val href = el.attr("href").ifBlank { el.attr("data-src") }
            if (href.isNotBlank()) {
                val fullUrl = fixUrl(href)
                candidateIframeUrls.add(fullUrl)
                if (fullUrl.contains("server.php")) {
                    candidateIframeUrls.add(fullUrl.replace("server.php", "serverplayerfree.php"))
                }
            }
        }

        // 2. Query each candidate player URL
        for (playerUrl in candidateIframeUrls) {
            val res = runCatching { Cf.get(playerUrl, referer = pageUrl) }.getOrNull() ?: continue
            val html = res.text
            val playerDoc = res.document

            // Check <video> and <source> tags
            val tagSources = playerDoc.select("video source[src], #instructions source[src], video[src], source[src]")
                .mapNotNull { it.attr("src").takeIf { s -> s.isNotBlank() } }

            for (src in tagSources) {
                if (emitStreamLink(src, base, playerUrl, callback)) {
                    found = true
                }
            }

            // Check for _RC_/proxy links in the player HTML
            val proxyMatches = Regex("""(?:href|src)=["']([^"']*_RC_/proxy\?src=[^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { it.groupValues[1] }
                .toList()

            for (proxyUrl in proxyMatches) {
                if (emitStreamLink(proxyUrl, base, playerUrl, callback)) {
                    found = true
                }
            }

            // Check for direct mp4/m3u8 URLs in inline JS scripts
            val directMatches = Regex("""["'](https?://[^"'\s<>]+\.(?:mp4|m3u8)[^"'\s<>]*)["']""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { it.groupValues[1] }
                .filterNot { it.contains("logo") || it.contains("banner") }
                .toList()

            for (stream in directMatches) {
                if (emitStreamLink(stream, base, playerUrl, callback)) {
                    found = true
                }
            }

            // Check for nested iframes
            playerDoc.select("iframe[src]").forEach { innerIframe ->
                val innerSrc = fixUrl(innerIframe.attr("src"))
                if (innerSrc != playerUrl && !innerSrc.contains("facebook") && !innerSrc.contains("twitter")) {
                    loadExtractor(innerSrc, playerUrl, subtitleCallback, callback)
                }
            }
        }

        return found
    }

    private fun emitStreamLink(
        rawUrl: String,
        base: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (rawUrl.isBlank()) return false
        val cleanUrl = fixUrl(rawUrl.replace("&amp;", "&"))

        val isM3u8 = cleanUrl.contains(".m3u8", ignoreCase = true)
        val isVideo = cleanUrl.contains(".mp4", ignoreCase = true) || cleanUrl.contains("_RC_/proxy", ignoreCase = true)

        if (!isM3u8 && !isVideo) return false

        // Emit the proxy stream with the exact RedeCanais headers
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "RedeCanais",
                url = cleanUrl,
                referer = "$base/",
                quality = Qualities.P1080.value,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                headers = mapOf(
                    "User-Agent" to Cfg.userAgent,
                    "Referer" to "$base/",
                    "Origin" to base
                )
            )
        )

        // Also decode the URL inside _RC_/proxy?src= to offer the direct CDN server link
        if (cleanUrl.contains("_RC_/proxy?src=")) {
            runCatching {
                val encodedTarget = cleanUrl.substringAfter("_RC_/proxy?src=").substringBefore("&")
                val decodedTarget = URLDecoder.decode(encodedTarget, "UTF-8")
                if (decodedTarget.startsWith("http")) {
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = "RedeCanais (Direto)",
                            url = decodedTarget,
                            referer = "$base/",
                            quality = Qualities.P1080.value,
                            type = if (decodedTarget.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                            headers = mapOf(
                                "User-Agent" to Cfg.userAgent,
                                "Referer" to "$base/"
                            )
                        )
                    )
                }
            }
        }

        return true
    }
}
