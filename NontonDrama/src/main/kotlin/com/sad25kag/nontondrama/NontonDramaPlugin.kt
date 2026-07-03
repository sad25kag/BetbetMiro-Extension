package com.sad25kag.nontondrama

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class NontonDramaPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(NontonDramaProvider())
    }
}
