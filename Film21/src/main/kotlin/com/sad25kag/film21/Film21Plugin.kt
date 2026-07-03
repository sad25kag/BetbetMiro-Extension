package com.sad25kag.film21

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class Film21Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Film21())
    }
}
