package com.lagradost.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class Episode(
    @param:JsonProperty("file") val file: String,
    @param:JsonProperty("title") var name: String,
    @param:JsonProperty("id") val id: String,
    @param:JsonProperty("poster") val poster: String,
    @param:JsonProperty("subtitle") val subtitle: String
)