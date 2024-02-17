package com.lagradost.models

data class TitleShows (

    val id : Int,
    val typeTitle : String,
    val title : String,
    val image : String,
    val imageTab : String,
    val imageMob : String,
    val logoImage : String,
    val description : String,
    val typeSlug : String,
    val channelSlug : String,
    val projectSlug : String,
    val videoSlug : String,
    val seasons : List<Seasons>,
    val video : String,
    val hash : String,
)

data class Seasons (

    val id : Int,
    val title : String,
    val seasonSlug : String
)