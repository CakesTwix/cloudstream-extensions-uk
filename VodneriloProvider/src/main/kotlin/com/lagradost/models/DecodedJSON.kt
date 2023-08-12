package com.lagradost.models

import com.google.gson.annotations.SerializedName


data class DecodedJSON (

    @SerializedName("tabName") val tabName : String,
    @SerializedName("seasons") val seasons : List<Seasons>
)

data class Seasons (

    @SerializedName("title") val title : String,
    @SerializedName("episodes") val episodes : List<Episodes>
)

data class Episodes (

    @SerializedName("title") val title : String,
    @SerializedName("sounds") val sounds : List<Sounds>
)

data class Sounds (

    @SerializedName("title") val title : String,
    @SerializedName("url") val url : String
)

data class AESPlayerDecodedModel (

    @SerializedName("tabName") val tabName : String,
    @SerializedName("url") val url : String
)

