package com.lagradost.models

import com.google.gson.annotations.SerializedName

data class FundubsModel (
    @SerializedName("funDubs") val fundubs : List<FundubModel>
)

data class FundubModel (
    @SerializedName("fundub") val fundub : Fundub,
    @SerializedName("player") val player : List<FundubPlayer>
)

data class Fundub (
    @SerializedName("id") val id : Int,
    @SerializedName("name") val name : String
)

data class FundubPlayer (
    @SerializedName("name") val name : String,
    @SerializedName("id") val id : Int
)

