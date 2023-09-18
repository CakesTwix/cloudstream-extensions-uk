package com.lagradost.models

import com.google.gson.annotations.SerializedName

data class ObjectsModel (

	@SerializedName("manga") val manga : List<Manga>,
	@SerializedName("video") val video : List<Video>,
	@SerializedName("korean") val korean : List<Korean>
)

data class Korean (

	@SerializedName("id") val id : Int,
	@SerializedName("name") val name : String,
	@SerializedName("eng_name") val eng_name : String,
	@SerializedName("orig_name") val orig_name : String,
	@SerializedName("author") val author : String,
	@SerializedName("sourse") val sourse : String,
	@SerializedName("tags") val tags : List<Tags>,
	@SerializedName("team") val team : String,
	@SerializedName("url") val url : String,
	@SerializedName("thumb") val thumb : String,
	@SerializedName("add_date") val add_date : String
)

data class Manga (

	@SerializedName("id") val id : Int,
	@SerializedName("name") val name : String,
	@SerializedName("eng_name") val eng_name : String,
	@SerializedName("orig_name") val orig_name : String,
	@SerializedName("author") val author : String,
	@SerializedName("sourse") val sourse : String,
	@SerializedName("tags") val tags : List<Tags>,
	@SerializedName("team") val team : String,
	@SerializedName("url") val url : String,
	@SerializedName("thumb") val thumb : String,
	@SerializedName("add_date") val add_date : String
)

data class Tags (
	@SerializedName("id") val id : String,
	@SerializedName("name") val name : String,
)
data class Video (

	@SerializedName("id") val id : Int,
	@SerializedName("name") val name : String,
	@SerializedName("eng_name") val eng_name : String,
	@SerializedName("orig_name") val orig_name : String,
	@SerializedName("studio") val studio : String,
	@SerializedName("tags") val tags : List<Tags>,
	@SerializedName("team") val team : String,
	@SerializedName("url") val url : String,
	@SerializedName("thumb") val thumb : String,
	@SerializedName("add_date") val add_date : String
)

data class CfgModel (

	@SerializedName("type") val type : String,
	@SerializedName("sources") val sources : List<Sources>,
	@SerializedName("tracks") val tracks : List<Tracks>,
	@SerializedName("poster") val poster : String
)

data class Sources (

	@SerializedName("src") val src : String,
	@SerializedName("size") val size : Int,
	@SerializedName("type") val type : String
)

data class Tracks (

	@SerializedName("src") val src : String,
	@SerializedName("label") val label : String,
	@SerializedName("srclang") val srclang : String,
	@SerializedName("kind") val kind : String,
	@SerializedName("default") val default : Boolean
)
