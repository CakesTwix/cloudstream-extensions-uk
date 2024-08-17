package com.lagradost.models


data class Search (
    val id : Int,
    val title : String,
    val age : Int,
    val image : String,
    val description : String,
    val typeSlug : String,
    val channelSlug : String,
    val projectSlug : String,
    val seasons : String
)