package com.lagradost.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class Season(
    @param:JsonProperty("title") val name: String,
    @param:JsonProperty("folder") val episodes: List<Episode>
)