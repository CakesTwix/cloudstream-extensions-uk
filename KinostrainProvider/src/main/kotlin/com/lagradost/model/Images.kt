package com.lagradost.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class Images(
    val poster: String? = null,
    val preview: List<String> = emptyList()
)
