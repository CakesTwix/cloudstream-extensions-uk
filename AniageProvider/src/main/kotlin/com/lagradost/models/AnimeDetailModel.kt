package com.lagradost.models

import com.google.gson.annotations.SerializedName

data class AnimeDetail (
    @SerializedName("pageProps") val pageProps : PageProps,
    @SerializedName("__N_SSP") val __N_SSP : Boolean
)

data class PageProps (

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
    @SerializedName("averageDuration") val averageDuration : Int,
    @SerializedName("teams") val teams : List<Teams>
)

data class Teams (

    @SerializedName("animeId") val animeId : String,
    @SerializedName("teamId") val teamId : String,
    @SerializedName("episodes") val episodes : Int,
    @SerializedName("views") val views : Int,
    @SerializedName("lastUpdated") val lastUpdated : String
)