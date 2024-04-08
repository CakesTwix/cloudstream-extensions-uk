package com.lagradost.models

import com.google.gson.annotations.SerializedName

class AnimeInfoModel (

    @SerializedName("id") val id : Int,
    @SerializedName("titleUa") val titleUa : String,
    @SerializedName("titleEn") val titleEn : String,
    @SerializedName("description") val description : String,
    @SerializedName("releaseDate") val releaseDate : Int,
    @SerializedName("producer") val producer : String,
    @SerializedName("views") val views : Int,
    @SerializedName("episodes") val episodes : Int,
    @SerializedName("episodeTime") val episodeTime : String,
    @SerializedName("poster") val poster : String,
    @SerializedName("backgroundImage") val backgroundImage : String,
    @SerializedName("episodesAired") val episodesAired : Int,
    @SerializedName("createdAt") val createdAt : String,
    @SerializedName("updatedAt") val updatedAt : String,
    @SerializedName("trailer") val trailer : String,
    @SerializedName("ashdiId") val ashdiId : String,
    @SerializedName("malId") val malId : Int,
    @SerializedName("pl") val pl : String,
    @SerializedName("season") val season : Int,
    @SerializedName("rating") val rating : Double,
    @SerializedName("genres") val genres : List<Genres>,
    @SerializedName("tags") val tags : List<Tags>,
    @SerializedName("studio") val studio : Studio,
    @SerializedName("status") val status : Status,
    @SerializedName("age") val age : Age,
    @SerializedName("fundups") val fundups : List<Fundups>,
    @SerializedName("type") val type : Type,
    @SerializedName("schedule") val schedule : Schedule,
    @SerializedName("franchise") val franchise : Franchise,
    @SerializedName("player") val player : Player,
    @SerializedName("screenshots") val screenshots : List<Screenshots>,
    @SerializedName("rank") val rank : Int,
    @SerializedName("votes") val votes : Int
)

data class Schedule (

        @SerializedName("id") val id : Int,
        @SerializedName("date") val name : String,
        @SerializedName("episode") val episode : String,
)

data class Age (

    @SerializedName("id") val id : Int,
    @SerializedName("name") val name : String
)

data class Franchise (

    @SerializedName("id") val id : Int,
    @SerializedName("weight") val weight : Int
)

data class Fundups (

    @SerializedName("id") val id : Int,
    @SerializedName("name") val name : String
)

data class Player (

    @SerializedName("id") val id : Int,
    @SerializedName("url") val url : String,
    @SerializedName("numEpisodeFrom") val numEpisodeFrom : String,
    @SerializedName("numEpisodeTo") val numEpisodeTo : String
)

data class Screenshots (

    @SerializedName("id") val id : Int,
    @SerializedName("original") val original : String,
    @SerializedName("preview") val preview : String
)

data class Status (

    @SerializedName("id") val id : Int,
    @SerializedName("name") val name : String
)

data class Studio (

    @SerializedName("id") val id : Int,
    @SerializedName("name") val name : String
)

data class Tags (

    @SerializedName("id") val id : Int,
    @SerializedName("name") val name : String
)

data class Type (

    @SerializedName("id") val id : Int,
    @SerializedName("name") val name : String
)
