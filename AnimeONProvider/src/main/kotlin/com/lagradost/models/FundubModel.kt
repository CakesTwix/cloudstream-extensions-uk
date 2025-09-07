package com.lagradost.models

import com.google.gson.annotations.SerializedName

data class FundubsModel (
        @SerializedName("funDubs") val fundubs : List<FundubModel>,
)

data class FundubModel (

        @SerializedName("fundub") val fundub : Fundub,
        @SerializedName("player") val player : List<FundubPlayer>
)

data class Fundub (

        @SerializedName("id") val id : Int,
        @SerializedName("name") val name : String,
        // @SerializedName("synonyms") val synonyms : List<String>,
        // @SerializedName("description") val description : String,
        // @SerializedName("team") val team : String,
        // @SerializedName("telegram") val telegram : String,
        // @SerializedName("youtube") val youtube : String,
        // @SerializedName("avatar") val avatar : String
)

data class FundubPlayer (

        @SerializedName("name") val name : String,
        @SerializedName("id") val id : Int
)
