package com.sad25kag.melongmovie

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MelongMoviePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MelongMovieProvider())
    }
}
