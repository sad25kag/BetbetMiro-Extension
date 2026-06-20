package com.sad25kag.javorb

data class JavOrbVideoCard(
    val title: String,
    val url: String,
    val posterUrl: String?
)

data class JavOrbVideoDetail(
    val title: String,
    val posterUrl: String?,
    val description: String?,
    val dvdId: String?,
    val duration: Int?,
    val year: Int?,
    val actors: List<String>,
    val tags: List<String>
)
