package com.lagradost.models

data class TranslationItem(
    val translation: Translation,
    val player: List<TranslationPlayer>
)

data class Translation(
    val id: Int,
    val name: String,
    val isSub: Boolean
)

data class TranslationPlayer(
    val name: String,
    val id: Int,
    val episodesCount: Int
)
