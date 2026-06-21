package com.sad25kag.gudangfilm

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class GudangFilmPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(GudangFilm())
    }
}
