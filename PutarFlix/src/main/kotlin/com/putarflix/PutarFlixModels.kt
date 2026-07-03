package com.putarflix

internal data class PutarFlixCategory(
    val path: String,
    val name: String
)

internal data class PutarFlixCard(
    val title: String,
    val url: String,
    val poster: String? = null,
    val quality: String? = null,
    val typeHint: String? = null,
    val year: Int? = null
)

internal data class PutarFlixEpisodeRef(
    val name: String,
    val url: String,
    val season: Int? = null,
    val episode: Int? = null
)

internal data class PutarFlixAjaxPlayer(
    val postId: String,
    val type: String,
    val nume: String,
    val label: String
)

internal data class PutarFlixServer(
    val label: String,
    val url: String,
    val referer: String,
    val source: String
)
