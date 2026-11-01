package com.redecanais

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolve o desafio do Cloudflare em um WebView, captura o cookie resultante e o
 * reaproveita nas requisições OkHttp. Só executa quando um desafio é detectado.
 */
object Cf {
    private const val SOLVE_TIMEOUT_MS = 40_000L
    private const val POLL_MS = 700L

    // Wildcard quebrado em concatenação para não gerar "estrela-barra" dentro da string.
    private const val ACCEPT_HTML =
        "text/html,application/xhtml+xml,application/xml;q=0.9," + "*" + "/" + "*" + ";q=0.8"

    private val cookies = ConcurrentHashMap<String, String>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun reset() = cookies.clear()

    private fun origin(url: String): String = runCatching {
        URL(url).let { "${it.protocol}://${it.host}" }
    }.getOrElse { url }

    private fun headers(url: String, referer: String?): MutableMap<String, String> =
        mutableMapOf(
            "User-Agent" to Cfg.userAgent,
            "Accept" to ACCEPT_HTML,
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
        ).apply {
            cookies[origin(url)]?.let { put("Cookie", it) }
            referer?.let { put("Referer", it) }
        }

    private fun isChallenge(code: Int, body: String): Boolean =
        body.contains("cf_clearance") ||
            body.contains("_cf_chl_opt") ||
            body.contains("Just a moment", true) ||
            body.contains("Attention Required", true) ||
            body.contains("cf-browser-verification", true) ||
            code == 403 || code == 503 || code == 429

    private suspend fun ensure(url: String, force: Boolean = false) {
        val o = origin(url)
        if (!force && cookies.containsKey(o)) return
        locks.getOrPut(o) { Mutex() }.withLock {
            if (!force && cookies.containsKey(o)) return@withLock
            solve(o)?.let { cookies[o] = it }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun solve(origin: String): String? = withContext(Dispatchers.Main) {
        val ctx = Cfg.ctx ?: return@withContext null
        var web: WebView? = null
        try {
            CookieManager.getInstance().setAcceptCookie(true)
            val view = WebView(ctx)
            web = view
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = Cfg.userAgent
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, u: String) {
                    CookieManager.getInstance().flush()
                }
            }
            view.loadUrl(origin)

            var waited = 0L
            while (waited < SOLVE_TIMEOUT_MS) {
                delay(POLL_MS)
                waited += POLL_MS
                val jar = CookieManager.getInstance().getCookie(origin)
                if (jar != null && jar.contains("cf_clearance")) {
                    CookieManager.getInstance().flush()
                    return@withContext jar
                }
            }
            null
        } catch (t: Throwable) {
            null
        } finally {
            web?.stopLoading()
            web?.destroy()
        }
    }

    suspend fun get(url: String, referer: String? = null): NiceResponse {
        ensure(url)
        var res = app.get(url, headers = headers(url, referer))
        if (isChallenge(res.code, res.text.take(5000))) {
            cookies.remove(origin(url))
            ensure(url, force = true)
            res = app.get(url, headers = headers(url, referer))
        }
        return res
    }

    suspend fun post(
        url: String,
        data: Map<String, String>,
        referer: String? = null
    ): NiceResponse {
        ensure(url)
        var res = app.post(url, data = data, headers = headers(url, referer))
        if (isChallenge(res.code, res.text.take(5000))) {
            cookies.remove(origin(url))
            ensure(url, force = true)
            res = app.post(url, data = data, headers = headers(url, referer))
        }
        return res
    }
}