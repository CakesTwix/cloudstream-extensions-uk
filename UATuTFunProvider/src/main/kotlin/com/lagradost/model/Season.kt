package com.lagradost.model

import com.google.gson.annotations.SerializedName

data class Season(
    @SerializedName("title") val name: String,
    @SerializedName("folder") val episodes: List<Episode>
)