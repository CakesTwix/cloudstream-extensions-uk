package com.lagradost.model

import com.fasterxml.jackson.annotation.JsonProperty

data class SeriesJsonDataModel(
    @JsonProperty("title") var seriesDubName: String,
    @JsonProperty("folder") var seasons: List<Season>
)