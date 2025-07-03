package com.lagradost.model

import com.fasterxml.jackson.annotation.JsonProperty

data class Episode(
    @JsonProperty("file") val file: String,
    @JsonProperty("title") var name: String,
    @JsonProperty("id") val id: String,
    @JsonProperty("poster") val poster: String,
    @JsonProperty("subtitle") val subtitle: String
)