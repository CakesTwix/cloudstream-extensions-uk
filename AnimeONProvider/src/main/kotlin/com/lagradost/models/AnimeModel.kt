package com.lagradost.models

import com.google.gson.annotations.SerializedName

class AnimeModel (
    @SerializedName("id") val id : Int,
    @SerializedName("titleUa") val titleUa : String,
    @SerializedName("image") val image : Image,
)