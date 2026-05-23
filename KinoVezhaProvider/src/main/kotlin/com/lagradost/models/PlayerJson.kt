package com.lagradost.models

data class PlayerJson (
	val title : String,
	val season : String,
	val folder : List<Episode>
)

data class Episode (
	val title : String,
	val file : String,
	val number: String,
	val poster : String? = null,
	val subtitle : String? = null,
)

