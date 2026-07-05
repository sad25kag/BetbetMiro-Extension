// use an integer for version numbers
version = 49


cloudstream {
    authors = listOf("sad25kag")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1 // will be 3 if unspecified

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime"
    )
    language = "id"
//  https://t0.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://hdhub4u.gratis&size=64
    iconUrl = "https://raw.githubusercontent.com/sad25kag/TVVVV/refs/heads/main/Icons/HDHUB.png"

    isCrossPlatform = false
}
