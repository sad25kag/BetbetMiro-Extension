package com.sad25kag.nontonanimex

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NontonAnimeXPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NontonAnimeX())
    }
}
