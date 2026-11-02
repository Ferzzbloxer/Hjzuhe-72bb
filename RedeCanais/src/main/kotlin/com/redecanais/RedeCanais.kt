package com.redecanais

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigInteger
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * RedeCanais (tema toroplay/WordPress).
 *
 * Fluxo real do site:
 *   /filme/<slug>/  |  /serie/<slug>/  |  /episodio/<slug>-temporadaN-episodioNN/
 *     -> ul.TPlayerNv > li[data-tplayernv]  (rótulo = DUBLADO / LEGENDADO)
 *     -> div.TPlayerTb#OptN > div[data-src] = /?trembed=0&trid=<postId>&trtype=1
 *     -> esse embed devolve iframe -> /player/film.php?id=<id ofuscado>  (player Drive)
 *     -> ou, em servidores alternativos, /_RC_/proxy?src=<url> | <video>/<source> | .mp4/.m3u8
 */
class RedeCanais : MainAPI() {

    override var name = "RedeCanais"

    override var mainUrl: String
        get() = Cfg.domain
        set(value) { Cfg.domain = value }

    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "pt"
    override val hasDownloadSupport = true
    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)

    private var warned = false

    override val mainPage: List<MainPageData>
        get() {
            val b = mainUrl.ifBlank { return emptyList() }
            return mainPageOf(
                "$b/filmes/" to "Filmes",
                "$b/series/" to "Séries",
                "$b/episodios/" to "Últimos Episódios",
                "$b/mais-visitados/" to "Mais Visitados",
                "$b/melhores-avaliacoes/" to "Melhores Avaliações"
            )
        }

    /* ------------------------- domínio / avisos ------------------------- */

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
            val res = fetch("$candidate/")
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

    /* ------------------------- rede + cloudflare ------------------------- */

    /**
     * Utiliza o helper Cf (WebView) do projeto para resolver Turnstile / Cloudflare clearance
     */
    private suspend fun fetch(url: String, referer: String? = null): NiceResponse =
        Cf.get(url, referer)

    private suspend fun doc(url: String, referer: String? = null): Document =
        fetch(url, referer).document

    /* ------------------------- parsing de cards ------------------------- */

    private fun Element.card(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val href = a.attr("href").let { fixUrl(it) }
        if (!href.contains("/filme/") && !href.contains("/serie/") && !href.contains("/episodio/")) return null

        val img = selectFirst("img")
        val title = (selectFirst("h1.Title, h2.Title, .Title")?.text()
            ?: img?.attr("alt")
            ?: a.attr("title"))?.trim()?.takeIf { it.isNotBlank() } ?: return null

        val poster = img?.let {
            it.attr("data-src").ifBlank { it.attr("data-echo").ifBlank { it.attr("src") } }
        }?.takeIf { it.isNotBlank() }

        val type = when {
            href.contains("/episodio/") || href.contains("/serie/") -> TvType.TvSeries
            title.contains("temporada", true) -> TvType.TvSeries
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
            this.year = Regex("""\(?(\d{4})\)?""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    private fun Document.cards(): List<SearchResponse> =
        select("article.TPost, div.TPost, li.TPost, div.MovieListSldCn article, .TPostBg + article")
            .distinctBy { it.selectFirst("a[href]")?.attr("href") }
            .mapNotNull { it.card() }
            .distinctBy { it.url }

    /* ------------------------- catálogo ------------------------- */

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = requireDomain() ?: return newHomePageResponse(request, emptyList())
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val items = runCatching { doc(url, "$base/").cards() }.getOrElse {
            runCatching { doc(request.data, "$base/").cards() }.getOrDefault(emptyList())
        }
        return newHomePageResponse(request, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (maybeSaveDomain(query)) return emptyList()
        val base = requireDomain() ?: return emptyList()
        val url = "$base/?s=${URLEncoder.encode(query, "UTF-8")}"
        return runCatching { doc(url, "$base/").cards() }.getOrDefault(emptyList())
    }

    /* ------------------------- detalhes ------------------------- */

    override suspend fun load(url: String): LoadResponse? {
        val base = requireDomain() ?: return null
        val pageUrl = fixUrl(url)
        val d = runCatching { doc(pageUrl, "$base/") }.getOrNull() ?: return null

        val title = d.selectFirst("h1.Title, h1")?.text()?.trim()
            ?: d.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        val poster = d.selectFirst("meta[property=og:image]")?.attr("content")
            ?: d.selectFirst("article img[src], .Image img[src]")?.attr("src")
        val plot = d.selectFirst(".Description p, .Description")?.text()?.trim()
        val year = Regex("""\(?(\d{4})\)?""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val tags = d.select("a[href*='/genero/']").map { it.text().trim() }.filter { it.isNotBlank() }

        val epAnchors = d.select("a[href*='/episodio/']").distinctBy { it.attr("href") }
        val isSeries = pageUrl.contains("/serie/") || epAnchors.isNotEmpty()

        if (isSeries) {
            val episodes = epAnchors.mapNotNull { el ->
                val href = fixUrl(el.attr("href"))
                val epTitle = el.text().trim().ifBlank { el.attr("title").trim() }
                val season = Regex("""temporada[-\s]?(\d+)""", RegexOption.IGNORE_CASE)
                    .find(href)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val number = Regex("""episodio[-\s]?(\d+)""", RegexOption.IGNORE_CASE)
                    .find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                newEpisode(href) {
                    this.name = epTitle.ifBlank { "T${season}E$number" }
                    this.season = season
                    this.episode = number
                    this.posterUrl = poster
                }
            }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

            if (episodes.isEmpty()) return null

            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
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
        val d = runCatching { doc(pageUrl, "$base/") }.getOrNull() ?: return false

        var found = false

        val tabs = d.select("li[data-tplayernv]").ifEmpty { null }?.map { tab ->
            val id = tab.attr("data-tplayernv")
            val label = tab.select("span").map { it.text().trim() }
                .filter { it.isNotBlank() && !it.equals("ASSISTIR", true) }
                .joinToString(" ")
                .ifBlank { "Player" }
            id to label
        }

        val containers: List<Pair<Element?, String>> = if (tabs != null) {
            tabs.map { (id, label) -> d.selectFirst(".TPlayerTb#$id") to label }
        } else {
            d.select(".TPlayerTb").map { it to "Player" }
        }

        val embeds = LinkedHashMap<String, String>()

        for ((container, label) in containers) {
            if (container == null) continue
            container.select("div[data-src], iframe[src], video[src], source[src]").forEach { el ->
                val raw = el.attr("data-src").ifBlank { el.attr("src") }.trim()
                if (raw.isNotBlank() && !raw.startsWith("data:")) embeds[fixUrl(raw)] = label
            }
        }
        
        d.select("a[href*='trembed'], button[data-src*='trembed'], li[data-src*='trembed']").forEach { el ->
            val raw = el.attr("href").ifBlank { el.attr("data-src") }.trim()
            if (raw.isNotBlank()) embeds[fixUrl(raw)] = el.text().trim().ifBlank { "Servidor" }
        }

        for ((embedUrl, label) in embeds) {
            val resolved = resolvePlayer(embedUrl, pageUrl, label, base, subtitleCallback, callback)
            if (resolved) found = true
        }

        return found
    }

    private suspend fun resolvePlayer(
        embedUrl: String,
        pageUrl: String,
        label: String,
        base: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = runCatching { fetch(embedUrl, pageUrl) }.getOrNull() ?: return false
        val html = res.text
        var found = false

        // 1) URLs diretas (proxy _RC_, mp4, m3u8, dash)
        streamRegexes(html).forEach { url ->
            if (emit(url, label, embedUrl, base, callback)) found = true
        }

        // 2) <video>/<source>
        res.document.select("video[src], video source[src], source[src]").forEach { el ->
            val src = el.attr("src").trim()
            if (src.isNotBlank() && emit(src, label, embedUrl, base, callback)) found = true
        }

        // 3) player Drive: /player/film.php?id=<id ofuscado>
        val playerUrls = Regex("""(?:src|data-src)=["']([^"']*/player/[^"']*\.php\?[^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).map { fixUrl(it.groupValues[1]) }.distinct().toList()

        for (playerUrl in playerUrls) {
            if (resolveDrivePlayer(playerUrl, label, base, callback)) found = true
        }

        // 4) iframes aninhados
        res.document.select("iframe[src]").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            if (src.isNotBlank() && src != embedUrl && !isJunk(src)) {
                runCatching { loadExtractor(src, embedUrl, subtitleCallback, callback) }
                    .onSuccess { if (it) found = true }
            }
        }

        return found
    }

    private suspend fun resolveDrivePlayer(
        playerUrl: String,
        label: String,
        base: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = runCatching { fetch(playerUrl, base).text }.getOrNull() ?: return false
        var found = false

        streamRegexes(html).forEach { if (emit(it, label, playerUrl, base, callback)) found = true }

        val encoded = Regex("""id=["']([0-9a-z]{20,})["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: Regex("""[?&]id=([0-9a-z]{20,})""", RegexOption.IGNORE_CASE)
                .find(playerUrl)?.groupValues?.get(1)

        val driveId = encoded?.let { decodeFileId(it) }
        if (driveId != null) {
            val driveUrl = "https://drive.google.com/file/d/$driveId/view"
            runCatching { loadExtractor(driveUrl, playerUrl, {}, callback) }
                .onSuccess { if (it) found = true }

            if (!found) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name · ${label.trim()}",
                        url = "https://drive.google.com/file/d/$driveId/preview",
                        referer = playerUrl,
                        quality = Qualities.P1080.value,
                        type = ExtractorLinkType.VIDEO,
                        headers = streamHeaders(base, playerUrl)
                    )
                )
                found = true
            }
        }
        return found
    }

    /* ------------------------- decode do id do player ------------------------- */

    private fun decodeFileId(encoded: String): String? {
        val clean = encoded.trim().lowercase().filter { it.isLetterOrDigit() }
        if (clean.length < 20) return null
        val chunks = clean.chunked(2).filter { it.length == 2 }

        // A) big integer em base 36² (1296) -> bytes ASCII
        runCatching {
            val n = chunks.fold(BigInteger.ZERO) { acc, p ->
                acc.multiply(BigInteger.valueOf(1296L)).add(BigInteger.valueOf(p.toInt(36).toLong()))
            }
            String(n.toByteArray(), Charsets.ISO_8859_1)
        }.getOrNull()?.let { if (looksLikeFileId(it)) return it }

        // B) pares base36 com keystream de paridade
        runCatching {
            buildString {
                chunks.forEachIndexed { i, p ->
                    val parity = if (PARITY_KEY[i % PARITY_KEY.length].code % 2 != 0) 1 else 0
                    append((p.toInt(36) - parity).toChar())
                }
            }
        }.getOrNull()?.let { if (looksLikeFileId(it)) return it }

        // C) hex direto
        runCatching {
            clean.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
        }.getOrNull()?.let { if (looksLikeFileId(it)) return it }

        return null
    }

    private fun looksLikeFileId(s: String): Boolean =
        s.length in 20..60 &&
            Regex("""^[A-Za-z0-9_-]+$""").matches(s) &&
            s.any { it.isDigit() } && s.any { it.isLetter() }

    /* ------------------------- emissão de links ------------------------- */

    private fun streamHeaders(base: String, referer: String) = mapOf(
        "User-Agent" to Cfg.userAgent,
        "Referer" to referer,
        "Origin" to base.trimEnd('/')
    )

    private fun streamRegexes(html: String): List<String> = buildList {
        Regex("""["'(]([^"'()\s]*_RC_/proxy\?src=[^"'()\s]+)""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { add(it.groupValues[1]) }
        Regex("""["'](https?://[^"'\s<>]+\.(?:mp4|m3u8|mpd)[^"'\s<>]*)""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { add(it.groupValues[1]) }
    }.map { it.replace("&amp;", "&") }
        .filterNot { it.contains("logo") || it.contains("banner") || it.contains("thumb") }
        .distinct()

    private fun isJunk(url: String): Boolean =
        url.contains("facebook") || url.contains("twitter") ||
            url.contains("whatsapp") || url.contains("doubleclick") || url.contains("googleads")

    private fun emit(
        raw: String,
        label: String,
        referer: String,
        base: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (raw.isBlank()) return false
        val url = fixUrl(raw.replace("&amp;", "&"))
        val lower = url.lowercase()

        val isProxy = lower.contains("_rc_/proxy")
        val isM3u8 = lower.contains(".m3u8")
        val isDash = lower.contains(".mpd")
        val isMp4 = lower.contains(".mp4")
        if (!isProxy && !isM3u8 && !isDash && !isMp4) return false

        val type = when {
            isM3u8 -> ExtractorLinkType.M3U8
            isDash -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        val quality = when {
            lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value
            lower.contains("1080") -> Qualities.P1080.value
            lower.contains("720") -> Qualities.P720.value
            lower.contains("480") -> Qualities.P480.value
            lower.contains("360") -> Qualities.P360.value
            else -> Qualities.P1080.value
        }

        callback.invoke(
            ExtractorLink(
                source = name,
                name = "$name · ${label.trim()}",
                url = url,
                referer = referer,
                quality = quality,
                type = type,
                headers = streamHeaders(base, referer)
            )
        )

        if (isProxy) {
            runCatching {
                val target = URLDecoder.decode(
                    url.substringAfter("src=").substringBefore("&"), "UTF-8"
                )
                if (target.startsWith("http")) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "$name · Direto",
                            url = target,
                            referer = referer,
                            quality = quality,
                            type = if (target.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                            headers = streamHeaders(base, referer)
                        )
                    )
                }
            }
        }
        return true
    }

    companion object {
        private const val PARITY_KEY = "ea26428b431e926"
    }
}
