package com.lagradost.models

data class PlayerJson(
    val title: String? = null,
    val folder: List<PlayerJson>? = null,
    val file: String? = null,
    val id: String? = null,
    val poster: String? = null,
    val subtitle: String? = null,
)
