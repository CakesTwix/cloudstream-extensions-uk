package com.lagradost.models

data class SeasonModel (

val id : Int,
val seoTitle : String,
val seoDescription : String,
val seoKeywords : String,
val typeTitle : String,
val channelTitle : String,
val channelImage : String,
val sortableCompilations : String,
val projectTitle : String,
val seasonTitle : String,
val age : String,
val image : String,
val imageTab : String,
val imageMob : String,
val logoImage : String,
val description : String,
val typeSlug : String,
val channelSlug : String,
val projectSlug : String,
val seasonSlug : String,
val personPage : Boolean,
// val persons : List<Persons>,
val seasons : List<Seasons>,
val seasonGallery : SeasonGallery,
)

data class SeasonGallery (

    val title : String,
    val seasonSlug : String,
    val items : List<Items>
)