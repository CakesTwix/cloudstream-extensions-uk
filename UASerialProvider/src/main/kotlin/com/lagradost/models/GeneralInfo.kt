package com.lagradost.models

import com.google.gson.annotations.SerializedName

data class GeneralInfo (

	// val @context : String,
	@SerializedName("@type") val type : String,
	val datePublished : String,
	val numberOfEpisodes : Int,
	val partOfTVSeries : PartOfTVSeries,
	val aggregateRating : AggregateRating,
	val url : String,
	val name : String
)

data class PartOfTVSeries (

	// val @type : String,
	val actor : List<Actor>,
	val director : List<Director>,
	val name : String,
	val containsSeason : List<ContainsSeason>
)

data class AggregateRating (

	// val @type : String,
	val ratingValue : Double,
	val ratingCount : Int,
	val bestRating : Int,
	val worstRating : Int
)

data class Actor (

	// val @type : String,
	val name : String
)

data class ContainsSeason (

	val url : String,
	// val @type : String,
	val seasonNumber : Int,
	val datePublished : String,
	val name : String,
	val episode : List<Episode>,
	val numberOfEpisodes : Int
)

data class Episode (

	// val @type : String,
	val episodeNumber : Int,
	val name : String
)

data class Director (

	// val @type : String,
	val name : String
)
