package com.lagradost.extractors

import com.lagradost.cloudstream3.app

class csstExtractor {
    suspend fun ParseUrl(url: String): String{
        return app.get(url).document.select("script").html()
            .substringAfterLast("file:\"")
            .substringBefore("\",")
    }
}