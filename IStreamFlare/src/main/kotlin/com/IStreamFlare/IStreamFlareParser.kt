package com.IStreamFlare

import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64DecodeArray
import com.sad25kag.BuildConfig
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class HomeRes(
    val TMDB_ID: String? = null,
    val banner: String? = null,
    val content_type: String? = null,
    val custom_tag: CustomTag? = null,
    val description: String? = null,
    val downloadable: String? = null,
    val genres: String? = null,
    val id: String = "",
    val name: String = "",
    val poster: String? = null,
    val release_date: String? = null,
    val runtime: String? = null,
    val status: String? = null,
    val type: String? = null,
    val youtube_trailer: String? = null,
    val url: String? = null,
)

data class CustomTag(
    val background_color: String? = null,
    val content_id: String? = null,
    val content_type: String? = null,
    val custom_tags_id: String? = null,
    val custom_tags_name: String? = null,
    val id: String? = null,
    val text_color: String? = null,
)

data class StreamLinks(
    val id: String = "",
    val name: String = "",
    val size: String = "",
    val quality: String = "",
    val link_order: String = "",
    val movie_id: String = "",
    val url: String = "",
    val type: String = "",
    val status: String = "",
    val skip_available: String = "",
    val intro_start: String = "",
    val intro_end: String = "",
    val end_credits_marker: String = "",
    val link_type: String = "",
    val drm_uuid: String = "",
    val drm_license_uri: String = "",
)

data class SeasonRes(
    val id: String = "",
    val Session_Name: String = "",
    val season_order: String = "",
    val web_series_id: String = "",
    val status: String = "",
)

data class EpisodesRes(
    val id: String = "",
    val Episoade_Name: String = "",
    val episoade_image: String = "",
    val episoade_description: String = "",
    val episoade_order: String = "",
    val season_id: String = "",
    val downloadable: String = "",
    val type: String = "",
    val status: String = "",
    val source: String = "",
    val url: String = "",
    val skip_available: String = "",
    val intro_start: String = "",
    val intro_end: String = "",
    val end_credits_marker: String = "",
    val drm_uuid: String = "",
    val drm_license_uri: String = "",
)

data class LoadDataObject(
    val id: String,
    val tmdbId: String?,
    val contentType: String?,
    val url: String?
)

data class Meta(
    val id: String?,
    val imdb_id: String?,
    val type: String?,
    val poster: String?,
    val logo: String?,
    val background: String?,
    val moviedb_id: Int?,
    val name: String?,
    val description: String?,
    val genre: List<String>?,
    val releaseInfo: String?,
    val status: String?,
    val runtime: String?,
    val cast: List<String>?,
    val language: String?,
    val country: String?,
    val imdbRating: String?,
    val slug: String?,
    val year: String?,
    val videos: List<EpisodeDetails>?
)

data class EpisodeDetails(
    val id: String?,
    val name: String?,
    val title: String?,
    val season: Int?,
    val episode: Int?,
    val released: String?,
    val overview: String?,
    val thumbnail: String?,
    val moviedb_id: Int?
)

data class ResponseData(
    val meta: Meta?
)

private val SECRET_KEY = base64Decode(BuildConfig.iStreamFlareSecretKey)
private const val SALT = BuildConfig.iStreamFlareSalt

fun decryptPayload(encryptedBase64: String): String {
    val decoded = base64DecodeArray(encryptedBase64)
    require(decoded.size >= 28) { "Invalid encrypted payload" }

    val iv         = decoded.copyOfRange(0, 12)
    val tag        = decoded.copyOfRange(12, 28)
    val ciphertext = decoded.copyOfRange(28, decoded.size)

    val key = deriveKey()

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))

    // Java uses ciphertext || tag
    val plaintext = cipher.doFinal(ciphertext + tag)
    return String(plaintext, Charsets.UTF_8)
}

private fun deriveKey(): SecretKey {
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val spec = PBEKeySpec(
        SECRET_KEY.toCharArray(),
        SALT.toByteArray(Charsets.UTF_8),
        10_000,
        256
    )
    return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
}

data class Response(
    val encrypted: Boolean,
    val data: String,
)
