package com.lagradost.models

data class Media(

    val galleryName : String,
    val typeSlug : String,
    val seoTitle : String,
    val seoDescription : String,
    val seoKeywords : String,
    val channels : List<String>,
    val items : List<Items>
)

data class Items (

    val title : String,
    val tizer : String,
    val image : String,
    val imageMob : String,
    val videoSlug : String,
    val seriesTitle : String,
)

