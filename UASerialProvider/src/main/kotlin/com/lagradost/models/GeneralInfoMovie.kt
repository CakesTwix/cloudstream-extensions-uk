package com.lagradost.models

import com.google.gson.annotations.SerializedName

data class GeneralInfoMovie (
	@SerializedName("@context") val context : String,
	@SerializedName("@type") val type : String,
	@SerializedName("aggregateRating") val aggregateRating : AggregateRatingMovie,
	@SerializedName("actor") val actor : List<ActorMovie>,
	@SerializedName("description") val description : String,
	@SerializedName("url") val url : String,
	@SerializedName("name") val name : String
)

data class AggregateRatingMovie (

	@SerializedName("@type") val type : String,
	@SerializedName("ratingValue") val ratingValue : Double,
	@SerializedName("ratingCount") val ratingCount : Int,
	@SerializedName("bestRating") val bestRating : Int,
	@SerializedName("worstRating") val worstRating : Int
)

data class ActorMovie (

	@SerializedName("@type") val type : String,
	@SerializedName("name") val name : String
)
