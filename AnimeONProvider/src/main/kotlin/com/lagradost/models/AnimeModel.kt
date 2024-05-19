package com.lagradost.models

import com.google.gson.annotations.SerializedName

class AnimeModel (
    @SerializedName("id") val id : Int,
    @SerializedName("titleUa") val titleUa : String,
    @SerializedName("image") val image : Image,
)

data class Image (

    @SerializedName("id") val id : Int,
    @SerializedName("original") val original : String,
    @SerializedName("preview") val preview : String
)