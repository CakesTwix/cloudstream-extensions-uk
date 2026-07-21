package com.lagradost.model

import com.fasterxml.jackson.annotation.JsonProperty

data class SchemaTvSeason(
    @JsonProperty("name") val name: String?,
    @JsonProperty("alternateName") val alternateName: String?,
    @JsonProperty("description") val description: String?,
    @JsonProperty("image") val image: String?,
    @JsonProperty("seasonNumber") val seasonNumber: String?,
    @JsonProperty("url") val url: String?,
    @JsonProperty("aggregateRating") val aggregateRating: SchemaRating?,
    @JsonProperty("actor") val actor: List<SchemaPerson>?,
    @JsonProperty("genre") val genre: List<String>?
)

data class SchemaRating(
    @JsonProperty("ratingValue") val ratingValue: String?,
    @JsonProperty("ratingCount") val ratingCount: String?
)

data class SchemaPerson(
    @JsonProperty("name") val name: String?
)
