package com.lagradost

import com.google.gson.annotations.SerializedName

data class JSONModel (

    @SerializedName("@context") val context : String,
    @SerializedName("@graph") val graph : List<graph>
)

data class graph (

    @SerializedName("@type") val type : String,
    @SerializedName("@context") val context : String,
    val publisher : Publisher,
    val name : String,
    val headline : String,
    val mainEntityOfPage : MainEntityOfPage,
    val datePublished : String,
    val dateModified : String,
    val author : Author,
    val image : List<String>,
    val description : String
)

data class Publisher (

    @SerializedName("@type") val type : String,
    val name : String
)

data class MainEntityOfPage (

    @SerializedName("@type") val type : String,
    @SerializedName("@id") val id : String
)

data class Author (

    @SerializedName("@type") val type : String,
    val name : String,
    val url : String
)
