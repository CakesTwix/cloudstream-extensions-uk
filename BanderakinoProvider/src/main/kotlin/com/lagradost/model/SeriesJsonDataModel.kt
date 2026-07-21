package com.lagradost.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class SeriesJsonDataModel(
    @param:JsonProperty("title") var seriesDubName: String,
    @param:JsonProperty("folder") var seasons: List<Season>
)