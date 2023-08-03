package com.lagradost.models

import com.google.gson.annotations.SerializedName

data class EpisodesModel(
    @SerializedName("views") val views : Int,
    @SerializedName("commented") val commented : Int,
    @SerializedName("id") val id : String,
    @SerializedName("animeId") val animeId : String,
    @SerializedName("teamId") val teamId : String,
    @SerializedName("volume") val volume : Int,
    @SerializedName("episodeNum") val episodeNum : Int,
    @SerializedName("subEpisodeNum") val subEpisodeNum : Int,
    @SerializedName("lastUpdated") val lastUpdated : String,
    @SerializedName("resourcePath") val resourcePath : String,
    @SerializedName("playPath") val playPath : String,
    @SerializedName("title") val title : String,
    @SerializedName("previewPath") val previewPath : String
)
