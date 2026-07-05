// use an integer for version numbers
version = 2

cloudstream {
    language = "id"
    // All of these properties are optional, you can safely remove them

    description = "Stream tokusatsu content including Kamen Rider, Super Sentai, Metal Heroes, and other Japanese special effect series with English subs"
    authors = listOf("sad25kag,YoureMotherHelped-Love-Mom")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "Anime"
    )

    iconUrl = "https://raw.githubusercontent.com/sad25kag/cloudstream-extensions-phisher/refs/heads/master/TokusatsuUltimate/TokusatsuUltimateIcon.png"
    isCrossPlatform = false
}
