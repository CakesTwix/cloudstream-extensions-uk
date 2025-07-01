package com.lagradost.models

data class PlayerJson (

	val title : String,
	val season : Int,
	val folder : List<Season>
)

data class Season (

	val title : String,
	val number: Int,
	val folder : List<Episode>
)

data class Episode (

	val title : String,
	val file : String,
	val id : String,
	val poster : String,
	val subtitle : String,
)
