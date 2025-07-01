package com.lagradost.models

data class GeneralInfo (

	val url : String,
	val name : String,
	val alternativeHeadline : String,
	val description : String,
	val image : String,
	val isFamilyFriendly : Boolean,
	val timeRequired : Int,
	val datePublished : String,
	val director : List<Director>?,
	val actor : List<Actor>,
	val countryOfOrigin : List<CountryOfOrigin>,
	val aggregateRating : AggregateRating?,
)

data class Director (

	val name : String
)

data class Actor (

	val name : String
)

data class CountryOfOrigin (

	val name : String
)

data class AggregateRating (

	val bestRating : Int,
	val ratingValue : Double,
	val ratingCount : Int
)


