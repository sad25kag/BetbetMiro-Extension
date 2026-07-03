package com.sad25kag.noxx

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class noxxPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Noxx())
        registerExtractorAPI(ByseSayeveum())
        registerExtractorAPI(MyvidplayAz())
        registerExtractorAPI(HqqAz())
        registerExtractorAPI(VidsrcXyzAz())
    }
}
