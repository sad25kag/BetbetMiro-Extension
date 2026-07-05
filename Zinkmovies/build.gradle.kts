// use an integer for version numbers
version = 2


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
    iconUrl = "https://raw.githubusercontent.com/sad25kag/TVVVV/refs/heads/main/Icons/zing.jpg"

    isCrossPlatform = true
}
