package com.sad25kag.dramaserial

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DramaSerialPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(DramaSerialProvider())
    }
}
