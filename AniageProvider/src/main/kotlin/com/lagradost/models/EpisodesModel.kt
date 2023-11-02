package com.lagradost.models

import com.google.gson.annotations.SerializedName

data class EpisodesModel(

    @SerializedName("views") val views : Int,
    @SerializedName("commented") val commented : Int,
    @SerializedName("id") val id : String,
    @SerializedName("animeId") val animeId : String,
    @SerializedName("teamId") val teamId : String,
    @SerializedName("volume") val volume : Int,
    @SerializedName("episodeNum") val episodeNum : Int,
    @SerializedName("subEpisodeNum") val subEpisodeNum : Int,
    @SerializedName("lastUpdated") val lastUpdated : String,
    @SerializedName("resourcePath") val resourcePath : String,
    @SerializedName("playPath") val playPath : String,
    @SerializedName("title") val title : String,
    @SerializedName("previewPath") val previewPath : String,
    @SerializedName("videoSource") val videoSource : VideoSource,
    @SerializedName("s3VideoSource") val s3VideoSource : S3VideoSource
)

data class VideoSource (

    @SerializedName("guid") val guid : String,
    @SerializedName("libraryId") val libraryId : Int,
    @SerializedName("playPath") val playPath : String,
    @SerializedName("previewPath") val previewPath : String,
    @SerializedName("errorMessage") val errorMessage : String,
    @SerializedName("videoStatus") val videoStatus : Int,
    @SerializedName("backupStatus") val backupStatus : String,
    @SerializedName("backup") val backup : Boolean,
    @SerializedName("lastUpdatedAt") val lastUpdatedAt : String
)

data class S3VideoSource (

    @SerializedName("id") val id : String,
    @SerializedName("previewPath") val previewPath : String,
    @SerializedName("playlistPath") val playlistPath : String,
    @SerializedName("bucket") val bucket : String,
    @SerializedName("lastUpdatedAt") val lastUpdatedAt : String
)
