package com.lagradost.models

data class ListPlayerJson (

	val list: List<PlayerJson>
)

data class PlayerJson (

	val title : String?,
	val file : String?,
	val folder: List<PlayerJson>?,
)
