package com.lagradost.models

data class Updates (

    val series : Series,
    val release : Release
)

data class Release (

    val id : Int,
    // vval lastUpdate : Int,
    val posterUuid : String,
    val code : String,
    val name : String,
    val type : String
)

data class Series (

    val id : Int,
    val imageUuid : String,
    val number : Int,
    val premium : Boolean,
    val duration : Int,
    val title : String,
    // val creationTimestamp : Int,
    val releaseId : Int
)