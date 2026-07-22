package com.lagradost.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class SeasonResponse(
    val type: String? = null,
    val data: SeasonData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SeasonData(
    val id: Int? = null,
    val name: String? = null,
    val description: String? = null,
    val year: Int? = null,

    @JsonProperty("season_number")
    val seasonNumber: String? = null,

    @JsonProperty("series_count")
    val seriesCount: Int? = null,

    val preview: PreviewImages? = null,
    val background: BackgroundImage? = null,
    val film: FilmInfo? = null,
    val categories: List<Category> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PreviewImages(
    val preview: String? = null,
    @JsonProperty("preview_main")
    val previewMain: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BackgroundImage(
    val background: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FilmInfo(
    val id: Int? = null,
    @JsonProperty("origin_name")
    val originName: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Category(
    val name: String? = null,
    @JsonProperty("seo_slug")
    val seoSlug: String? = null
)
