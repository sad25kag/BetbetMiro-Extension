package com.sad25kag.nodrasia

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NoDrasiaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NoDrasia())
    }
}
