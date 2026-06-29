package com.lagradost.models

data class FundubEpisode(
    val id: Int,
    val episode: Int,
    val subtitles: Boolean,
    val poster: String?,
    val fileUrl: String?,
    val videoUrl: String?
)
