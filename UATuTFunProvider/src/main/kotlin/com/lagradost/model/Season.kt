package com.lagradost.model

import com.fasterxml.jackson.annotation.JsonProperty

data class Season(
    @JsonProperty("title") val name: String,
    @JsonProperty("folder") val episodes: List<Episode>
)