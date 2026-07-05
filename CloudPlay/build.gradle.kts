@file:Suppress("UnstableApiUsage")

version = 4

android {
    defaultConfig {
        android.buildFeatures.buildConfig = true
    }
}

cloudstream {
    language = "id"
    requiresResources = false
    description = "CloudPlay Live TV Extension"
    authors = listOf("sad25kag")

    status = 1
    tvTypes = listOf(
        "Live",
    )

    iconUrl = "https://raw.githubusercontent.com/sad25kag/TVVVV/refs/heads/main/Icons/cloudplay.jpg"

    isCrossPlatform = false
}
