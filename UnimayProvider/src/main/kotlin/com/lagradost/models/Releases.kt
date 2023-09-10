package com.lagradost.models

data class Releases(

    val titleCount : Int,
    val releases : List<Release>
)

data class Release (

    val id : Int,
    val imageId : Int,
    val episodes : Int,
    val playlistSize : Int,
    val episodeLength : Int,
    val year : Int,
    val restricted : Boolean,
    val code : String,
    val description : String,
    val name : String,
    val season : String,
    val statusCode : Int
)
