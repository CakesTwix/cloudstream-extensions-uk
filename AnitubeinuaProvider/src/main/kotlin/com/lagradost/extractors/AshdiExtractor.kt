package com.lagradost.extractors

import com.lagradost.cloudstream3.app

class AshdiExtractor() {
    private val fileRegex = "file\\s*:\\s*['\"]([^'\"]+)['\"]".toRegex()

    suspend fun ParseM3U8(url: String): String {
        val scriptHtml = app.get(url).document.select("script").html()
        return fileRegex.findAll(scriptHtml).lastOrNull()?.groupValues?.get(1) ?: ""
    }
}
