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
    @SerializedName("image") val image : Image,
    @SerializedName("malId") val malId : Int,
    @SerializedName("rating") val rating : Double,
    @SerializedName("status") val status : String?,
    @SerializedName("type") val type : String?,
    @SerializedName("genres") val genres : List<Genres>
)