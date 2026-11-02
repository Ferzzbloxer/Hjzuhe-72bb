package com.redecanais

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Calendar

class RedeCanais : MainAPI() {

    override var name = "RedeCanais"

    override var mainUrl: String
        get() = Cfg.domain
        set(value) { Cfg.domain = value }

    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "pt"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.OVA)

    private var warned = false

    override val mainPage: List<MainPageData>
        get() {
            val b = mainUrl.ifBlank { return emptyList() }
            return mainPageOf(
                "$b/filme/" to "Filmes",
                "$b/serie/" to "Séries",
                "$b/serie/?filter=animes" to "Animes",
                "$b/serie/?filter=novelas" to "Novelas",
                "$b/ano/${Calendar.getInstance().get(Calendar.YEAR)}/" to "Lançamentos"
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
            toast("Informe o endereço do RedeCanais: digite a URL no campo de busca (ex.: https://redecanais.by)")
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
        val href = selectFirst("a[href]")?.attr("href") ?: return null
        if (!href.contains("/filme/") && !href.contains("/serie/")) return null
        val title = selectFirst("h3, h4, .title")?.text()?.trim()
            ?: selectFirst("img")?.attr("alt")?.trim() ?: return null
        val type = if (href.contains("/filme/")) TvType.Movie else TvType.TvSeries
        return newMovieSearchResponse(title, fixUrl(href), type) {
            posterUrl = selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            year = Regex("""(\d{4})""").find(text())?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    /* ------------------------- catálogo ------------------------- */

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = requireDomain() ?: return newHomePageResponse(request, emptyList())
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = Cf.get(url, referer = "$base/").document
        return newHomePageResponse(
            request,
            doc.select("div.items article.item, div.items div.item").mapNotNull { it.toSearchResult() }
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (maybeSaveDomain(query)) return emptyList()
        val base = requireDomain() ?: return emptyList()
        val doc = Cf.get("$base/search/${URLEncoder.encode(query, "UTF-8")}/", referer = "$base/").document
        return doc.select("div.items article.item, div.items div.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val base = requireDomain() ?: return null
        val doc = Cf.get(fixUrl(url), referer = "$base/").document

        val title = doc.selectFirst("div.sheader h1")?.text()
            ?.substringBefore(" - RedeCanais")?.trim() ?: return null
        val poster = doc.selectFirst("div.sheader div.poster img")?.attr("src")
        val plot = doc.selectFirst("#info .wp-content")?.text()?.trim()
        val year = Regex("""\d{4}""")
            .find(doc.selectFirst("div.sheader span.date")?.text().orEmpty())?.value?.toIntOrNull()
        val tags = doc.select("div.sgeneros a").map { it.text().trim() }

        if (url.contains("/filme/")) {
            return newMovieLoadResponse(title, fixUrl(url), TvType.Movie, fixUrl(url)) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        val episodes = mutableListOf<Episode>()
        doc.select("#seasons .se-c").forEach { season ->
            val seasonNum = season.selectFirst("span.se-t")?.text()?.trim()?.toIntOrNull() ?: 1
            season.select("ul.episodios li a[href]").forEach { a ->
                val epUrl = fixUrl(a.attr("href"))
                val epNum = Regex("""(\d+)\s*x\s*(\d+)""")
                    .find(a.selectFirst(".numerando")?.text().orEmpty())
                    ?.groupValues?.get(2)?.toIntOrNull()
                    ?: Regex("""(\d+)x(\d+)""").find(epUrl)?.groupValues?.get(2)?.toIntOrNull()
                episodes += newEpisode(epUrl) {
                    this.name = a.selectFirst(".episode-title")?.text()?.trim() ?: "T$seasonNum E$epNum"
                    this.season = seasonNum
                    this.episode = epNum
                    this.posterUrl = a.selectFirst("img")?.attr("src")
                }
            }
        }

        if (episodes.isEmpty() && doc.selectFirst("#playeroptions li[data-post]") != null) {
            episodes += newEpisode(fixUrl(url)) { this.name = "Assistir" }
        }

        return newTvSeriesLoadResponse(title, fixUrl(url), TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    /* ------------------------- players ------------------------- */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val base = requireDomain() ?: return false
        val pageUrl = fixUrl(data)
        val doc = Cf.get(pageUrl, referer = "$base/").document

        var foundLinks = false

        // 1. Try DooPlay options if present
        val options = doc.select("#playeroptions li[data-post], ul.playeroptions li")
        if (options.isNotEmpty()) {
            options.amap { opt ->
                val label = opt.selectFirst("span.title, .title")?.text()?.trim()
                    ?.replace(Regex("""[▶️\s]+"""), " ")?.trim().orEmpty()
                val post = opt.attr("data-post").ifBlank { opt.attr("data-id") }
                val nume = opt.attr("data-nume")
                val type = opt.attr("data-type").ifBlank { if (pageUrl.contains("/filme/")) "movie" else "tv" }

                val embed = if (post.isNotBlank() && nume.isNotBlank()) {
                    getEmbedUrl(post, nume, type, base)
                } else null

                val target = embed?.let { unwrapEmbed(it, pageUrl) }
                if (target != null) {
                    loadExtractor(target, pageUrl, subtitleCallback) { link ->
                        foundLinks = true
                        callback.invoke(
                            ExtractorLink(
                                source = this.name,
                                name = "[${label.ifBlank { "Player" }}] ${link.name}",
                                url = link.url,
                                referer = pageUrl,
                                quality = getQualityFromName(label),
                                type = link.type,
                                headers = link.headers
                            )
                        )
                    }
                }
            }
        }

        // 2. Fallback for Movies: Look for direct iframes on the page
        if (!foundLinks) {
            val iframes = doc.select("iframe[src], div.playbox iframe, #playerep iframe")
                .mapNotNull { it.attr("src").takeIf { src -> src.isNotBlank() } }
                .filterNot { it.contains("facebook") || it.contains("twitter") || it.contains("disqus") }

            iframes.amap { rawIframe ->
                val target = unwrapEmbed(fixUrl(rawIframe), pageUrl) ?: fixUrl(rawIframe)
                loadExtractor(target, pageUrl, subtitleCallback) { link ->
                    foundLinks = true
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = "[Filme] ${link.name}",
                            url = link.url,
                            referer = pageUrl,
                            quality = link.quality,
                            type = link.type,
                            headers = link.headers
                        )
                    )
                }
            }
        }

        return foundLinks
    }

    private suspend fun getEmbedUrl(post: String, nume: String, type: String, base: String): String? {
        val raw = runCatching {
            Cf.post(
                "$base/wp-admin/admin-ajax.php",
                data = mapOf("action" to "doo_player_ajax", "post" to post, "nume" to nume, "type" to type),
                referer = "$base/"
            ).text
        }.getOrNull().orEmpty()

        parseEmbed(raw)?.let { return it }

        val rest = runCatching {
            Cf.post(
                "$base/wp-json/dooplayer/v1/player",
                data = mapOf("id" to post, "nume" to nume, "type" to type),
                referer = "$base/"
            ).text
        }.getOrNull().orEmpty()

        return parseEmbed(rest)
    }

    private fun parseEmbed(raw: String): String? {
        if (raw.isBlank()) return null
        val json = raw.replace("\\/", "/")
        val url = runCatching { JSONObject(json).optString("embed_url") }.getOrNull()
            ?.takeIf { it.startsWith("http") }
            ?: Regex("""embed_url"\s*:\s*"([^"]+)"""").find(json)
                ?.groupValues?.get(1)?.replace("\\/", "/")
        return url?.takeIf { it.startsWith("http") }
    }

    private suspend fun unwrapEmbed(embedUrl: String, referer: String): String? {
        if (Regex("""\.(m3u8|mp4)(\?|$)""").containsMatchIn(embedUrl)) return embedUrl
        val html = runCatching { Cf.get(embedUrl, referer = referer).text }.getOrNull() ?: return embedUrl
        val iframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
        val source = Regex("""(?:file|src)\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""")
            .find(html)?.groupValues?.get(1)
        return (source ?: iframe)?.let { fixUrl(it) } ?: embedUrl
    }
}