package com.lagradost.models

data class SearchModel (

    val id : Int,
    val aniListId : Int,
    val episodes : Int,
    val episodeLength : Int,
    val year : Int,
    val restricted : Boolean,
    val active : Boolean,
    val announce : String,
    val code : String,
    val tgCode : String,
    val cdnCode : String,
    val description : String,
    val lastUpdate : Long,
    val name : String,
    val engName : String,
    val trailer : String,
    // https://api.unimay.media/api/release/suzume-locking-up-the-doors
    // val trailerPlaylist : String?,
    val timestamp : Long,
    val season : String,
    val type : String,
    val posterId : Int,
    val imageId : Int,
    val genres : List<String>,
    val playlist : List<Playlist>,
    val playlistSize : Int,
    val actors : List<Actors>,
    val translators : List<Translators>,
    val soundmans : List<Soundmans>,
    val genresInString : String,
    val statusCode : Int
)

data class Actors (

    val id : Int,
    val firstName : String,
    val lastName : String,
    val nickName : String,
    val city : String,
    val avtarId : Int
)

data class Playlist (

    val id : Int,
    val number : Int,
    val title : String,
    val preview : String?,
    val previewId : Int?,
    val sd : String?,
    val hd : String?,
    val fhd : String?,
    val qhd : String?,
    val playlist : String?,
    val hidden : Boolean
)

data class Soundmans (

    val id : Int,
    val firstName : String,
    val lastName : String,
    val nickName : String,
    val city : String,
    val avtarId : Int
)

data class Translators (

    val id : Int,
    val firstName : String,
    val lastName : String,
    val nickName : String,
    val city : String,
    val avtarId : Int
)