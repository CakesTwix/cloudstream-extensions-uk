package com.lagradost.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnimeItem(
    val type: String? = null,
    val data: SeriesData? = null
)
