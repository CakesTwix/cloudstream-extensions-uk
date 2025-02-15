package com.lagradost.models

import com.google.gson.annotations.SerializedName

class AnimeInfoModel (

    @SerializedName("id") val id : Int,
    @SerializedName("titleUa") val titleUa : String,
    @SerializedName("titleEn") val titleEn : String,
    @SerializedName("description") val description : String,
    @SerializedName("releaseDate") val releaseDate : Int,
    @SerializedName("episodeTime") val episodeTime : String,
    @SerializedName("poster") val poster : String,
    @SerializedName("backgroundImage") val backgroundImage : String,
    @SerializedName("trailer") val trailer : String,
    @SerializedName("malId") val malId : Int,
    @SerializedName("rating") val rating : Double,
    @SerializedName("genres") val genres : List<Genres>,
    @SerializedName("status") val status : String?,
    @SerializedName("type") val type : String?,
    @SerializedName("player") val player : List<Player>,
)

data class Genres (

        @SerializedName("id") val id : Int,
        @SerializedName("name") val name : String,
        @SerializedName("malId") val malId : String,
)

data class Player (

    @SerializedName("id") val id : Long,
    @SerializedName("url") val url : String,
    @SerializedName("numEpisodeFrom") val numEpisodeFrom : String,
    @SerializedName("numEpisodeTo") val numEpisodeTo : String
)

data class Status (

    @SerializedName("id") val id : Int,
    @SerializedName("name") val name : String
)

data class Type (

    @SerializedName("id") val id : Int,
    @SerializedName("name") val name : String
)
