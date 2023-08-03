package com.lagradost.models

import com.google.gson.annotations.SerializedName

data class TeamsModel (
    @SerializedName("socials") val socials : List<Socials>,
    @SerializedName("id") val id : String,
    @SerializedName("ownerId") val ownerId : String,
    @SerializedName("description") val description : String,
    @SerializedName("name") val name : String,
    @SerializedName("logo") val logo : String,
    @SerializedName("type") val type : String
)

data class Socials (

    @SerializedName("url") val url : String,
    @SerializedName("type") val type : String
)