package com.lagradost.models

import com.google.gson.annotations.SerializedName


data class FindModel (

    // @SerializedName("cursorNext") val cursorNext : CursorNext?,
    // @SerializedName("cursorPrev") val cursorPrev : String?,
    @SerializedName("counter") val counter : Int,
    @SerializedName("data") val data : List<Data>
)

data class CursorNext (

    @SerializedName("page") val page : Int,
    @SerializedName("pageSize") val pageSize : Int,
    @SerializedName("order") val order : Order,
    @SerializedName("cleanup") val cleanup : List<String>
)

data class Data (

    @SerializedName("id") val id : String,
    @SerializedName("posterId") val posterId : String,
    @SerializedName("title") val title : String,
    @SerializedName("alternativeTitle") val alternativeTitle : String,
    @SerializedName("type") val type : String,
    @SerializedName("titleStatus") val titleStatus : String,
    @SerializedName("publishedAt") val publishedAt : String,
    @SerializedName("genres") val genres : List<String>,
    @SerializedName("description") val description : String,
    @SerializedName("season") val season : String,
    @SerializedName("studios") val studios : List<String>,
    @SerializedName("adult") val adult : Int,
    @SerializedName("episodes") val episodes : Int,
    @SerializedName("trailerUrl") val trailerUrl : String,
    @SerializedName("maxEpisodes") val maxEpisodes : Int,
    @SerializedName("averageDuration") val averageDuration : Int
)

data class Order (

    @SerializedName("by") val by : String,
    @SerializedName("direction") val direction : String
)