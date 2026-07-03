package com.sad25kag.nonton21

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class Nonton21Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Nonton21())
    }
}
