package com.lagradost.models

import com.google.gson.annotations.SerializedName

data class PlayerEpisodes (
    @SerializedName("episodes") val episodes: List<FundubEpisode>?,
    @SerializedName("anotherPlayer") val anotherPlayer: AnotherPlayer?
)

data class AnotherPlayer(
    @SerializedName("name") val name: String,
    @SerializedName("id") val id: Int,
    @SerializedName("episodeCount") val episodeCount: Int
)

data class FundubEpisode (
    @SerializedName("id") val id : Int,
    @SerializedName("episode") val episode : Int,
    @SerializedName("subtitles") val subtitles : Boolean,
    @SerializedName("poster") val poster : String,
    @SerializedName("fileUrl") val fileUrl : String?,
    @SerializedName("videoUrl") val videoUrl : String?
)