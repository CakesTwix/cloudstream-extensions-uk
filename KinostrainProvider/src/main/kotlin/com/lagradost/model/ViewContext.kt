package com.lagradost.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class ViewContext(
    @JsonProperty("viewed_status")
    val viewedStatus: String? = null,

    val progress: Int? = null,

    @JsonProperty("progress_percent")
    val progressPercent: Int? = null,

    @JsonProperty("is_viewed")
    val isViewed: Boolean? = null
)
