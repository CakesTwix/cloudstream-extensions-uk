package com.lagradost.extractors;

import com.lagradost.cloudstream3.app

class AshdiExtractor() {
    suspend fun ParseM3U8(url: String): String{
        return app.get(url).document.select("script").html()
            .substringAfterLast("file:\"")
            .substringBefore("\",")

    }
}
