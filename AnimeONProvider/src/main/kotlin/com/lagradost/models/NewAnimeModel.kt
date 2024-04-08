package com.lagradost.models

import com.google.gson.annotations.SerializedName

class NewAnimeModel  (

    @SerializedName("results") val results : List<Results>,
    @SerializedName("totalCount") val totalCount : Int
)

data class Results (

    @SerializedName("id") val id : Int,
    @SerializedName("titleUa") val titleUa : String,
    @SerializedName("description") val description : String,
    @SerializedName("releaseDate") val releaseDate : Int,
    @SerializedName("views") val views : Int,
    @SerializedName("episodes") val episodes : Int,
    @SerializedName("poster") val poster : String,
    @SerializedName("episodesAired") val episodesAired : Int,
    @SerializedName("createdAt") val createdAt : String,
    @SerializedName("malId") val malId : Int,
    @SerializedName("rating") val rating : Double,
    @SerializedName("status") val status : Status,
    @SerializedName("type") val type : Type,
    @SerializedName("genres") val genres : List<Genres>
)