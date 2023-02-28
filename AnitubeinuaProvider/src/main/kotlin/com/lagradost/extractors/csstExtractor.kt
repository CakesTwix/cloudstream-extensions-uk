package com.lagradost.extractors

import android.util.Log
import com.lagradost.cloudstream3.app

class csstExtractor {
    suspend fun ParseUrl(url: String): String{
        val playerLinks = app.get(url).document.select("script").html()
            .substringAfterLast("file:\"")
            .substringBefore("\",")

        Log.d("load-debug", playerLinks)

        return playerLinks
    }
}