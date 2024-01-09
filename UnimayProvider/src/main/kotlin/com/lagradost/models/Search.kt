package com.lagradost.models

data class SearchModel (

    val code : String,
    val year : Int,
    val description : String,
    val type : String,
    val engName : String,
    // val translators : List<Translators>,
    val playlist : List<Playlist>,
    val genres : List<String>,
    val aniListId : Int,
    val imageId : Int,
    val name : String,
    val posterId : Int,
    val statusCode : Int,
    val episodeLength : Int,
)

data class Playlist (

    val preview : Int,
    val title : String,
    val number : Int,
    val previewId : Int,
    val playlist : String,
)


