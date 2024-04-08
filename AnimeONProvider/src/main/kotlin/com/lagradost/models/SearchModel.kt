package com.lagradost.models

import com.google.gson.annotations.SerializedName

class SearchModel (

    @SerializedName("result") val result : List<Result>,
    @SerializedName("count") val count : Int
)

data class Result (

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
    @SerializedName("ashdiId") val ashdiId : String,
    @SerializedName("malId") val malId : Int,
    @SerializedName("season") val season : Int,
    @SerializedName("rating") val rating : Double,
    @SerializedName("type") val type : Type,
    @SerializedName("status") val status : Status
)
