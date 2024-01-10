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

data class SearchGet (
    val content : List<Content>,
)

data class Content (

    val id : Int,
    val playlistSize : Int,
    val code : String,
    val names : Names,
    val images : Images,
)

data class Images (

    val banner : Int?,
    val poster : Int
)

data class Names (

    val romaji : String,
    val ukr : String,
    val eng : String
)
