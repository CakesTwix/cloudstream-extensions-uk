package com.lagradost.models

import com.google.gson.annotations.SerializedName

class SearchModel (

    @SerializedName("result") val result : List<Result>,
)

data class Result (

    @SerializedName("titleUa") val titleUa : String,
    @SerializedName("id") val id : Int,
    @SerializedName("image") val image : Image,
    @SerializedName("episodes") val episodes : Int,
)
