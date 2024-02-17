package com.lagradost.models

data class VideoPlayer (

    val version : String,
    val type : String,
    val poster : String,
    val posterMob : String,
    val name : String,
    val video : List<Video>
)

data class Video (

    val vcmsId : Int,
    val hash : String,
    val channel : String,
    val channel_domain : String,
    val project_id : String,
    val projectName : String,
    val seasonName : String,
    val releaseName : String,
    val year_of_production : Int,
    val countries_of_production : List<String>,
    val content_language : List<String>,
    val publishDate : String,
    val date_of_broadcast : String,
    val time_upload_video : String,
    val cache_time : String,
    val current_time : String,
    val videoAccessible : Boolean,
    val videoAccessible_type : String,
    val autoplay : Boolean,
    val showadv : Boolean,
    val anons : Boolean,
    val program : String,
    val name : String,
    val duration : Int,
    val poster : String,
    val projectPostURL : String,
    val preview_url : String,
    val tt : String,
    val plug_type : String,
    val canonicalPageUrl : String,
    val media : List<Media>,
    val mediaHls : String,
    val mediaHlsNoAdv : String,
    val availableHls : Int
)