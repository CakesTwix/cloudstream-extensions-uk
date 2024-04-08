package com.lagradost.models

import com.google.gson.annotations.SerializedName

class AnimeModel (
    @SerializedName("id") val id : Int,
    @SerializedName("titleUa") val titleUa : String,
    @SerializedName("description") val description : String,
    @SerializedName("poster") val poster : String,
    @SerializedName("backgroundImage") val backgroundImage : String,
    @SerializedName("rating") val rating : Double,
    @SerializedName("genres") val genres : List<Genres>
)

data class Genres (

    @SerializedName("id") val id : Int,
    @SerializedName("name") val name : String,
    @SerializedName("malId") val malId : Int
)