package com.lagradost.models

data class PlayerJson (

		val title : String,
		val folder : List<Season>
)

data class Season (

		val title : String,
		val folder : List<Episode>
)

data class Episode (

		val title : String,
		val file : String,
		val id : String,
		val poster : String,
		val subtitle : String,
)
