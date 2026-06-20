package com.sad25kag.javorb

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class JavOrbPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(JavOrbProvider())
    }
}
