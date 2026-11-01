package com.redecanais

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.buildSettings

@CloudstreamPlugin
class RedeCanaisPlugin : Plugin() {
    override fun load(context: Context) {
        Cfg.init(context)
        registerMainAPI(RedeCanais())
        registerLiveAPI(com.redecanais.tv.RedeCanaisTV())

        registerSettings(
            buildSettings {
                textInput(
                    key = "domain",
                    title = "Endereço do RedeCanais",
                    description = "Ex.: https://redecanais.by — troque quando o domínio mudar",
                    value = Cfg.domain
                )
            }
        ) { settings ->
            Cfg.domain = settings.getString("domain", "")
            Cf.reset()
        }
    }
}