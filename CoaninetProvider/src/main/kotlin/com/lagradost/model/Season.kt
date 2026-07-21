package com.lagradost.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class Season(
    val id: Int? = null,
    val part: String? = null,
    val name: String? = null
)
