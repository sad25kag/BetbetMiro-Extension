package com.sad25kag.garasifilm21

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class GarasiFilm21Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(GarasiFilm21Provider())
    }
}
