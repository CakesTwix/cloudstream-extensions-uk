package com.lagradost.extractors

import com.lagradost.cloudstream3.app

class AshdiExtractor() {
    suspend fun ParseM3U8(url: String): String {
        val scriptHtml = app.get(url).document.select("script").html()
        // Site uses single quotes: file:'https://...m3u8'
        return scriptHtml
            .substringAfterLast("file:'")
            .substringBefore("'")
    }
}