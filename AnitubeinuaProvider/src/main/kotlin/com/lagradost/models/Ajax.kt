package com.lagradost.models

data class Ajax (

    val numberEpisode : Int?,
    val name : String,
    val urls : Link,
)

data class Link (
    val isDub : Boolean,
    val url : String,
    val name : String,
    val playerName : String,
)