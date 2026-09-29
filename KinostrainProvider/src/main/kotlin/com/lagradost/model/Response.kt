package com.lagradost.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovieResponse(
    @JsonProperty("data") val data: List<MovieItem>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovieItem(
    @JsonProperty("name") val name: String,
    @JsonProperty("originalName") val originalName: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("type") val type: String,
    @JsonProperty("format") val format: String,
    @JsonProperty("imdbMark") val imdbMark: Double?,
    @JsonProperty("posterUrl") val posterUrl: String?,
    @JsonProperty("yearStart") val yearStart: Int?,
    @JsonProperty("yearEnd") val yearEnd: Int?,
    @JsonProperty("firstReadySeason") val firstReadySeason: FirstReadySeason?,
    @JsonProperty("highlight") val highlight: Highlight?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FirstReadySeason(
    @JsonProperty("number") val number: Int?,
    @JsonProperty("lastReadyEpisode") val lastReadyEpisode: Int?,
    @JsonProperty("readyEpisodesCount") val readyEpisodesCount: Int?,
    @JsonProperty("lastUrlSuffix") val lastUrlSuffix: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Highlight(
    @JsonProperty("name") val name: String
)

