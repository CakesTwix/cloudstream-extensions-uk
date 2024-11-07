package com.lagradost.models

import com.google.gson.annotations.SerializedName

data class SearchModel (
    @SerializedName("movies") val movies : List<Movies>,
)

data class Movies (
    @SerializedName("link") val link : String,
    @SerializedName("name") val name : String,
    @SerializedName("poster") val poster : String
)