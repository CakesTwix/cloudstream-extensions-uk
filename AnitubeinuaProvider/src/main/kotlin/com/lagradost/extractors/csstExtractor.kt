package com.lagradost.extractors

import com.lagradost.cloudstream3.app

class csstExtractor {
    private val fileRegex = "file\\s*:\\s*['\"]([^'\"]+)['\"]".toRegex()

    suspend fun ParseUrl(url: String): String {
        val scriptHtml = app.get(url).document.select("script").html()
        return fileRegex.findAll(scriptHtml).lastOrNull()?.groupValues?.get(1) ?: ""
    }
}
