package com.sad25kag.Donghuastream

import com.fasterxml.jackson.annotation.JsonProperty

data class Root(
    val status: String,
    @field:JsonProperty("server_time")
    val serverTime: String,
    val query: Query,
    @field:JsonProperty("embed_link")
    val embedLink: String,
    @field:JsonProperty("download_link")
    val downloadLink: String,
    @field:JsonProperty("request_link")
    val requestLink: String,
    val title: String,
    val poster: String,
    val sources: List<Source>,
    val tracks: List<Track>,
)

data class Query(
    val source: String,
    val id: String,
    val alt: String,
)

data class Source(
    val file: String,
    val type: String,
    val label: String,
    val default: Boolean,
)

data class Track(
    val file: String,
    val label: String,
    val default: Boolean?,
)
