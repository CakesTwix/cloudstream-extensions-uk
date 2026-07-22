package com.lagradost.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class Response(
    val data: List<Item>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Item(
    val data: Anime
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Anime(
    val name: String,

    @JsonProperty("seo_slug")
    val seoSlug: String,

    @JsonProperty("film_seo_slug")
    val filmSeoSlug: String,

    val preview: Preview
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Preview(
    @JsonProperty("preview_main")
    val previewMain: String
)
