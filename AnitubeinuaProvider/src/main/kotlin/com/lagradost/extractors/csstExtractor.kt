package com.lagradost.extractors

import com.lagradost.AnitubeSubtitle
import com.lagradost.cloudstream3.app
import com.lagradost.distinctSubtitles
import com.lagradost.parsePlayerJsSubtitles

class csstExtractor {
    private val fileRegex = "file\\s*:\\s*['\"]([^'\"]+)['\"]".toRegex()
    private val subtitleRegex = "subtitle\\s*:\\s*['\"]([^'\"]*)['\"]".toRegex()

    /** Рядок якостей у форматі `[1080p]url,[720p]url` плюс субтитри плеєра. */
    data class Source(val file: String, val subtitles: List<AnitubeSubtitle>)

    suspend fun ParseUrl(url: String): String = parseSource(url).file

    suspend fun parseSource(url: String): Source {
        val scriptHtml = try {
            app.get(url).document.select("script").html()
        } catch (e: Exception) {
            return Source("", emptyList())
        }

        return Source(
            fileRegex.findAll(scriptHtml).lastOrNull()?.groupValues?.get(1) ?: "",
            parsePlayerJsSubtitles(
                subtitleRegex.findAll(scriptHtml).lastOrNull()?.groupValues?.get(1)
            ).distinctSubtitles(),
        )
    }
}
