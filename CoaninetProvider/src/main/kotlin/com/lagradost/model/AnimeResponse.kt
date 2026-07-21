package com.lagradost.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnimeResponse(
    val type: String? = null,
    val data: List<AnimeItem> = emptyList(),
    val meta: List<Any> = emptyList()
)
