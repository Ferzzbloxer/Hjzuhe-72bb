package com.redecanais

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RedeCanaisPlugin : Plugin() {
    override fun load(context: Context) {
        Cfg.init(context)
        registerMainAPI(RedeCanais())
    }
}