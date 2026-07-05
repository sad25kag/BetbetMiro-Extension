// use an integer for version numbers
import org.jetbrains.kotlin.konan.properties.Properties

version = 1

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    defaultConfig {
        val properties = Properties()
        properties.load(project.rootProject.file("local.properties").inputStream())
        android.buildFeatures.buildConfig=true
        buildConfigField("String", "iStreamFlare", "\"${properties.getProperty("iStreamFlare")}\"")
        buildConfigField("String", "iStreamFlareSecretKey", "\"${properties.getProperty("iStreamFlareSecretKey")}\"")
        buildConfigField("String", "iStreamFlareSalt", "\"${properties.getProperty("iStreamFlareSalt")}\"")

    }
}

cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Movies/Series/Anime"
    language    = "id"
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
        "AsianDrama",
        "TvSeries",
        "Anime",
        "Movie",
        "Cartoon",
        "AnimeMovie"
    )
    iconUrl="https://raw.githubusercontent.com/sad25kag/TVVVV/refs/heads/main/Icons/streamflare.png"

    isCrossPlatform = false
}
