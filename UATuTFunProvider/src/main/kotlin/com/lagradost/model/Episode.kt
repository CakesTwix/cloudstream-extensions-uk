package com.lagradost.model

import com.google.gson.annotations.SerializedName

data class Episode(
    @SerializedName("title") val name: String,
    val file: String,
    val id: String,
    val poster: String,
    val subtitle: String
)