package com.lagradost.models

import com.google.gson.annotations.SerializedName

class AnimeInfoModel (

    @SerializedName("id"               ) var id               : Int,
    @SerializedName("titleUa"          ) var titleUa          : String,
    @SerializedName("titleEn"          ) var titleEn          : String,
    @SerializedName("titleOriginal"    ) var titleOriginal    : String,
    @SerializedName("description"      ) var description      : String,
    @SerializedName("releaseDate"      ) var releaseDate      : String,
    @SerializedName("producer"         ) var producer         : String,
    @SerializedName("views"            ) var views            : Int,
    @SerializedName("episodes"         ) var episodes         : Int,
    @SerializedName("episodeTime"      ) var episodeTime      : String,
    // @SerializedName("moonId"           ) var moonId           : String,
    @SerializedName("backgroundImage"  ) var backgroundImage  : String,
    // @SerializedName("episodesAired"    ) var episodesAired    : Int,
    @SerializedName("createdAt"        ) var createdAt        : String,
    // @SerializedName("updatedAt"        ) var updatedAt        : String,
    @SerializedName("trailer"          ) var trailer          : String,
    // @SerializedName("imdbId"           ) var imdbId           : String,
    @SerializedName("malId"            ) var malId            : String,
    // @SerializedName("season"           ) var season           : Int,
    @SerializedName("rating"           ) var rating           : String,
    // @SerializedName("synonyms"         ) var synonyms         : List<String>,
    @SerializedName("seasonOfYear"     ) var seasonOfYear     : String,
    // @SerializedName("malScored"        ) var malScored        : String,
    @SerializedName("malScoredBy"      ) var malScoredBy      : Int,
    // @SerializedName("source"           ) var source           : String,
    @SerializedName("dateStart"        ) var dateStart        : String,
    // @SerializedName("dateEnd"          ) var dateEnd          : String,
    @SerializedName("age"              ) var age              : String,
    @SerializedName("type"             ) var type             : String,
    @SerializedName("status"           ) var status           : String,
    @SerializedName("genres"           ) var genres           : List<Genres>,
    // @SerializedName("tags"             ) var tags             : List<Tags>,
    // @SerializedName("studio"           ) var studio           : Studio,
    // @SerializedName("schedule"         ) var schedule         : String,
    // @SerializedName("franchise"        ) var franchise        : Franchise,
    // @SerializedName("player"           ) var player           : List<Player>,
    @SerializedName("image"            ) var image            : Image,
    @SerializedName("screenshots"      ) var screenshots      : List<Screenshots>,
    // @SerializedName("votes"            ) var votes            : Int,
    // @SerializedName("rank"             ) var rank             : Int,
    // @SerializedName("fundubs"          ) var fundubs          : List<Fundubs>,
    // @SerializedName("ratesStatusStats" ) var ratesStatusStats : List<RatesStatusStats>

)

data class Genres (

    @SerializedName("nameEn" ) var nameEn : String,
    @SerializedName("nameUa" ) var nameUa : String,
    @SerializedName("malId"  ) var malId  : Int,
    @SerializedName("slug"   ) var slug   : String

)

data class Tags (

    @SerializedName("id"   ) var id   : Int,
    @SerializedName("name" ) var name : String

)

data class Studio (

    @SerializedName("id"   ) var id   : Int,
    @SerializedName("name" ) var name : String

)

data class Franchise (

    @SerializedName("id"     ) var id     : Int,
    @SerializedName("weight" ) var weight : Int

)

data class Player (

    @SerializedName("name" ) var name : String

)

data class Image (

    @SerializedName("id"       ) var id       : Int,
    @SerializedName("original" ) var original : String,
    @SerializedName("preview"  ) var preview  : String

)

data class Screenshots (

    @SerializedName("id"       ) var id       : Int,
    @SerializedName("original" ) var original : String,
    @SerializedName("preview"  ) var preview  : String

)

data class Fundubs (

    @SerializedName("id"          ) var id          : Int,
    @SerializedName("name"        ) var name        : String,
    // @SerializedName("synonyms"    ) var synonyms    : String?,
    @SerializedName("description" ) var description : String,
    @SerializedName("team"        ) var team        : String,
    @SerializedName("telegram"    ) var telegram    : String,
    @SerializedName("youtube"     ) var youtube     : String

)


data class RatesStatusStats (

    @SerializedName("name"  ) var name  : String,
    @SerializedName("value" ) var value : Int

)