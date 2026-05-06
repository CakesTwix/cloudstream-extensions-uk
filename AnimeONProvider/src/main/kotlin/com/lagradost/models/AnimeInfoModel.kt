package com.lagradost.models

import com.google.gson.annotations.SerializedName

class AnimeInfoModel (
    @SerializedName("id" ) var id : Int,
    @SerializedName("titleUa" ) var titleUa : String,
    @SerializedName("titleEn" ) var titleEn : String,
    @SerializedName("titleOriginal" ) var titleOriginal : String,
    @SerializedName("description" ) var description : String,
    @SerializedName("releaseDate" ) var releaseDate : String,
    @SerializedName("producer" ) var producer : String,
    @SerializedName("views" ) var views : Int,
    @SerializedName("episodes" ) var episodes : Int,
    @SerializedName("episodeTime" ) var episodeTime : String,
    @SerializedName("backgroundImage" ) var backgroundImage : String,
    @SerializedName("episodesAired" ) var episodesAired : Int,
    @SerializedName("createdAt" ) var createdAt : String,
    @SerializedName("updatedAt" ) var updatedAt : String,
    @SerializedName("trailer" ) var trailer : String,
    @SerializedName("malId" ) var malId : String,
    @SerializedName("rating" ) var rating : String,
    @SerializedName("type" ) var type : String,
    @SerializedName("status" ) var status : String,
    @SerializedName("genres" ) var genres : List<Genres>,
    @SerializedName("image" ) var image : Image,
    @SerializedName("slug" ) var slug : String? = null
)

data class Genres (
    @SerializedName("nameEn" ) var nameEn : String,
    @SerializedName("nameUa" ) var nameUa : String
)

data class Image (
    @SerializedName("preview" ) var preview : String
)
