// use an integer for version numbers
version = 5

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.14.0")
}

cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Animes"
    authors = listOf("sad25kag")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    language = "id"
    iconUrl = "https://anidb.app/images/fav-512.png"
    isCrossPlatform = false
    requiresResources = true
}
