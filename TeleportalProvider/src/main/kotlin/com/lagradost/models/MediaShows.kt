package com.lagradost.models

data class MediaShows (

    val galleryName : String,
    val typeSlug : String,
    val seoTitle : String,
    val seoDescription : String,
    val seoKeywords : String,
    val channels : List<Channels>,
    val items : List<ItemsShows>
)

data class Channels (

    val title : String,
    val channelSlug : String,
    val seoTitle : String,
    val seoDescription : String,
    val seoKeywords : String
)

data class ItemsShows (

    val name : String,
    val image : String,
    val imageMob : String,
    val channelSlug : String,
    val projectSlug : String
)