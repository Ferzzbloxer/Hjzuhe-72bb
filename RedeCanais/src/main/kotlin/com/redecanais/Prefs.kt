package com.redecanais

import android.content.Context
import java.net.URL

/**
 * Configuração persistente. Nada de domínio no repositório: o usuário informa na
 * primeira busca ou na tela de configurações.
 */
object Cfg {
    private const val PREFS = "redecanais_prefs"
    private const val KEY_DOMAIN = "domain"
    private const val KEY_UA = "ua"

    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    @Volatile
    private var appContext: Context? = null

    val ctx: Context? get() = appContext

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun sp() = requireNotNull(appContext) { "Cfg.init não chamado" }
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var domain: String
        get() = sp().getString(KEY_DOMAIN, "").orEmpty()
        set(value) = sp().edit().putString(KEY_DOMAIN, normalize(value)).apply()

    val isConfigured: Boolean get() = domain.isNotBlank()

    /** "redecanais.by", "https://www17.redecanais.in/" → "https://redecanais.by" / "https://www17.redecanais.in" */
    fun normalize(raw: String): String {
        var d = raw.trim().removeSuffix("/")
        if (d.isEmpty()) return ""
        if (!d.startsWith("http", true)) d = "https://$d"
        return runCatching {
            val host = URL(d).host
            if (host.isNullOrBlank() || !host.contains(".")) "" else "https://$host"
        }.getOrDefault("")
    }

    /** UA fixo: precisa ser idêntico no WebView e no OkHttp (cf_clearance é vinculado ao UA). */
    val userAgent: String
        get() = sp().getString(KEY_UA, null) ?: FALLBACK_UA.also {
            sp().edit().putString(KEY_UA, it).apply()
        }
}