package com.lagradost

import android.util.Log
import com.lagradost.cloudstream3.app

class Tracker {
    suspend fun getTracker(title: String?, type: String?, year: Int?): Tracker {
        val res = app.get("https://api.consumet.org/meta/anilist/$title")
            .parsedSafe<AniSearch>()?.results?.find { media ->
                Log.d("load-debug", media.toString())
                (media.title?.english.equals(title, true) || media.title?.romaji.equals(
                    title,
                    true
                ))
            }
        return Tracker(res?.malId, res?.aniId, res?.image, res?.cover)
    }

    data class Tracker(
        val malId: Int? = null,
        val aniId: String? = null,
        val image: String? = null,
        val cover: String? = null,
    )

    data class Title(
        val romaji: String? = null,
        val english: String? = null,
    )

    data class Results(
        val aniId: String? = null,
        val malId: Int? = null,
        val title: Title? = null,
        val releaseDate: Int? = null,
        val type: String? = null,
        val image: String? = null,
        val cover: String? = null,
    )

    data class AniSearch(
        val results: ArrayList<Results>? = arrayListOf(),
    )

    private data class Episodes(
        val file: String? = null,
        val title: String? = null,
        val poster: String? = null,
    )

    private data class Home(
        val table: String? = null,
    )

    private data class Search(
        val mes: String? = null,
    )
}