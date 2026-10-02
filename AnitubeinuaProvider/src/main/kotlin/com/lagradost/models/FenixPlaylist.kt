package com.lagradost.models

import com.google.gson.annotations.SerializedName

/** Конфіг плеєра FENIX з `window.FENIX_PLAYLIST`. */
data class FenixPlaylist(
    @SerializedName("episodes") val episodes: List<FenixEpisode>?,
    @SerializedName("mini_episodes") val miniEpisodes: List<FenixEpisode>?,
)

data class FenixEpisode(
    @SerializedName("number") val number: Int?,
    @SerializedName("title") val title: String?,
    @SerializedName("edition_label") val editionLabel: String?,
    @SerializedName("hls_master_url") val hlsMasterUrl: String?,
    @SerializedName("tracks") val tracks: List<FenixTrack>?,
    @SerializedName("subtitles") val subtitles: List<FenixSubtitle>?,
)

data class FenixTrack(
    @SerializedName("label") val label: String?,
    @SerializedName("language") val language: String?,
    @SerializedName("track_type") val trackType: String?,
    @SerializedName("hls_url") val hlsUrl: String?,
    @SerializedName("is_default") val isDefault: Boolean?,
)

/**
 * Трек субтитрів. `kind` = "full" (повні) або "signs" (написи),
 * `is_forced` позначає форсовані субтитри, які плеєр показує поверх озвучення.
 */
data class FenixSubtitle(
    @SerializedName("label") val label: String?,
    @SerializedName("language") val language: String?,
    @SerializedName("kind") val kind: String?,
    @SerializedName("url") val url: String?,
    @SerializedName("is_default") val isDefault: Boolean?,
    @SerializedName("is_forced") val isForced: Boolean?,
)

/**
 * `window.FENIX_MEDIA_ACCESS` — медіа-домен і токен.
 * Без токена media.fenixplay.xyz віддає 403 і на відео, і на субтитри.
 */
data class FenixMediaAccess(
    @SerializedName("origin") val origin: String?,
    @SerializedName("token") val token: String?,
)
