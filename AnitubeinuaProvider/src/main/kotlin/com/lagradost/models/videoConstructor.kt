package com.lagradost.models

data class videoConstructor (
    val playerName: String,
    val episodeName: String,
    var episodeNumber: Int?,
    val episodeUrl: String
)