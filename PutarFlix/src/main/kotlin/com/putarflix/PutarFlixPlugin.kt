package com.putarflix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PutarFlixPlugin : Plugin() {
    override fun load() {
        registerMainAPI(PutarFlixProvider())

        // PutarFlix mirrors the same movie-host ecosystem used by the working
        // Dutamovie/LayarKaca references: Muvipro tabs, playeriframe/Hownetwork,
        // Majorplay, StreamWish clones, and VidStack-style embeds.
        registerExtractorAPI(PutarFlixEmturbovid())
        registerExtractorAPI(PutarFlixP2P())
        registerExtractorAPI(PutarFlixF16())
        registerExtractorAPI(PutarFlixJeniusplay())
        registerExtractorAPI(PutarFlixMajorplay())
        registerExtractorAPI(PutarFlixE2eMajorplay())
        registerExtractorAPI(PutarFlixM3u8Majorplay())
        registerExtractorAPI(PutarFlixHglink())
        registerExtractorAPI(PutarFlixGhbrisk())
        registerExtractorAPI(PutarFlixDhcplay())
        registerExtractorAPI(PutarFlixStreamcasthub())
        registerExtractorAPI(PutarFlixDm21embed())
        registerExtractorAPI(PutarFlixDm21upns())
        registerExtractorAPI(PutarFlixDm21())
        registerExtractorAPI(PutarFlixBangjago())
        registerExtractorAPI(PutarFlixHiguys())
        registerExtractorAPI(PutarFlixMeplayer())
        registerExtractorAPI(PutarFlixPlayPutarIn())
        registerExtractorAPI(PutarFlixLk21PlayerPage())
        registerExtractorAPI(PutarFlixGdplayer())
        registerExtractorAPI(PutarFlixAWSStream())
        registerExtractorAPI(PutarFlixMegaPlay())
        registerExtractorAPI(PutarFlixLuluStream())
        registerExtractorAPI(PutarFlixFiledon())
        registerExtractorAPI(PutarFlixBloggerVideo())
        registerExtractorAPI(PutarFlixPlayStreamplay())
        registerExtractorAPI(PutarFlixMovearnpre())
        registerExtractorAPI(PutarFlixCallistanise())
        registerExtractorAPI(PutarFlixBoosterx())
    }
}
