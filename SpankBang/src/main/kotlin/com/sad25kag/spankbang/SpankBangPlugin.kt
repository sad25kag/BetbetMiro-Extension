package com.sad25kag.spankbang

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SpankBangPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SpankBang())
    }
}
