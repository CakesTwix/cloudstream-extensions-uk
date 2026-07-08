package com.lagradost.models

data class PlayerJson(
    val title: String,
    val folder: List<PlayerJson>? = null,
    val file: String? = null,
    val id: String? = null,
    val poster: String? = null,
    val subtitle: String? = null
)

data class DubFolder(
    val title: String,
    val folder: List<EpisodeFolder>? = null
)

data class EpisodeFolder(
    val title: String,
    val file: String? = null,
    val id: String? = null,
    val poster: String? = null,
    val subtitle: String? = null
)