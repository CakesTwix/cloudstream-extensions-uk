package com.lagradost.models

import com.google.gson.annotations.SerializedName

data class FundubEpisode (

        @SerializedName("id") val id : Int,
        @SerializedName("episode") val episode : Int,
        @SerializedName("subtitles") val subtitles : Boolean,
        @SerializedName("poster") val poster : String
)