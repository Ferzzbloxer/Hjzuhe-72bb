package com.redecanais.tv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TvPlugin : Plugin() {
    override fun load(context: Context) {
        com.redecanais.Cfg.init(context)
        registerLiveAPI(RedeCanaisTV())
    }
}