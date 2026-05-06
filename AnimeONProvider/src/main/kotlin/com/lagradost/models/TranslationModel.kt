package com.lagradost.models

import com.google.gson.annotations.SerializedName

data class TranslationsResponse(
    @SerializedName("translations") val translations: List<TranslationItem>
)

data class TranslationItem(
    @SerializedName("translation") val translation: Translation,
    @SerializedName("player") val player: List<TranslationPlayer>
)

data class Translation(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("isSub") val isSub: Boolean
)

data class TranslationPlayer(
    @SerializedName("name") val name: String,
    @SerializedName("id") val id: Int,
    @SerializedName("episodesCount") val episodesCount: Int
)
