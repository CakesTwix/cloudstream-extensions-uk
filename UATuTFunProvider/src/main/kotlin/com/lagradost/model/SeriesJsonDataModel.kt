package com.lagradost.model

import com.google.gson.annotations.SerializedName

data class SeriesJsonDataModel(
    @SerializedName("title") val seriesDubName: String,
    @SerializedName("folder") val seasons: List<Season>
)