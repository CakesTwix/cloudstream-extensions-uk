package com.lagradost.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class SeriesData(
    val context: ViewContext? = null,
    val hash: String? = null,
    val number: Int? = null,
    val duration: Int? = null,
    val views: Int? = null,

    @JsonProperty("voice_type")
    val voiceType: String? = null,

    val season: Season? = null,
    val images: Images? = null,

    @JsonProperty("sort_order")
    val sortOrder: Int? = null,

    val video: String? = null
)
