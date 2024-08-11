package com.lagradost.models

data class SearchModel (

    val content : List<Content>,
    val pageable : Pageable,
    val totalElements : Int,
    val last : Boolean,
    val totalPages : Int,
    val size : Int,
    val number : Int,
    val sort : Sort,
    val numberOfElements : Int,
    val first : Boolean,
    val empty : Boolean
)

data class Content (

    val id : Int,
    val aniListId : Int,
    // val lastUpdate : Int,
    val episodes : Int,
    val playlistSize : Int,
    val statusCode : Int,
    val year : Int,
    val adult : Boolean,
    val restricted : Boolean,
    val code : String,
    val type : String,
    val season : String,
    val description : String,
    val names : Names,
    val images : Images,
    val genres : List<String>
)

data class Pageable (

    val sort : Sort,
    val pageNumber : Int,
    val pageSize : Int,
    val offset : Int,
    val paged : Boolean,
    val unpaged : Boolean
)

data class Sort (

    val sorted : Boolean,
    val empty : Boolean,
    val unsorted : Boolean
)