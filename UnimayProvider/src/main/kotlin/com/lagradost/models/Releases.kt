package com.lagradost.models

data class Releases(

    val id : Int,
    val aniListId : Int,
    // val lastUpdate : Int,
    // val lastEdit : Int,
    val statusCode : Int,
    val episodes : Int,
    val episodeLength : Int,
    val year : Int,
    val playlistSize : Int,
    val commentsCount : Int,
    val restricted : Boolean,
    val adult : Boolean,
    val partOfFranchise : Boolean,
    val announcement : String,
    val collaboration : String,
    val code : String,
    val imdbId : String,
    val tgCode : String,
    val trailer : String,
    val type : String,
    val season : String,
    val description : String,
    val names : Names,
    val images : Images,
    val genres : List<String>,
    val playlist : List<Playlist>,
    val actors : List<Actors>,
    val translators : List<Translators>,
    val soundmen : List<Soundmen>
)

data class Actors (

    val id : Int,
    val imageUuid : String,
    val nickName : String
)

data class Hls (

    val qualities : Qualities,
    val master : String
)

data class Images (

    val banner : String,
    val logo : String,
    val poster : String
)

data class Names (

    val romaji : String,
    val ukr : String,
    val eng : String
)

data class Playlist (

    val id : Int,
    val imageUuid : String,
    val number : Int,
    val duration : Int,
    val premium : Boolean,
    val thumbnails : Boolean,
    val uuid : String,
    val title : String,
    // val creationTimestamp : Int,
    val hls : Hls
)

data class Qualities (

    val fhd : String,
    val hd : String,
    val qhd : String
)

data class Soundmen (

    val id : Int,
    val imageUuid : String,
    val nickName : String
)

data class Translators (

    val id : Int,
    val imageUuid : String,
    val nickName : String
)