package com.lagradost.extractors

import com.lagradost.AnitubeSubtitle
import com.lagradost.cloudstream3.app
import com.lagradost.distinctSubtitles
import com.lagradost.parseHlsSubtitles
import com.lagradost.parsePlayerJsSubtitles

class AshdiExtractor() {
    private val fileRegex = "file\\s*:\\s*['\"]([^'\"]+)['\"]".toRegex()
    private val subtitleRegex = "subtitle\\s*:\\s*['\"]([^'\"]*)['\"]".toRegex()

    /** Відео плюс субтитри з одного й того ж конфігу плеєра. */
    data class Source(val file: String, val subtitles: List<AnitubeSubtitle>)

    suspend fun ParseM3U8(url: String): String = parseSource(url).file

    suspend fun parseSource(url: String): Source {
        val scriptHtml = try {
            app.get(url).document.select("script").html()
        } catch (e: Exception) {
            return Source("", emptyList())
        }

        val file = fileRegex.findAll(scriptHtml).lastOrNull()?.groupValues?.get(1) ?: ""
        val playerSubtitles = parsePlayerJsSubtitles(
            subtitleRegex.findAll(scriptHtml).lastOrNull()?.groupValues?.get(1)
        )

        // Якщо окремого поля `subtitle:` немає, форсовані субтитри можуть бути
        // треком усередині HLS-манифеста — тоді дивимось у сам плейлист.
        val subtitles = if (playerSubtitles.isNotEmpty() || file.isBlank()) {
            playerSubtitles
        } else {
            playerSubtitles + hlsSubtitles(file)
        }

        return Source(file, subtitles.distinctSubtitles())
    }

    private suspend fun hlsSubtitles(masterUrl: String): List<AnitubeSubtitle> = try {
        parseHlsSubtitles(app.get(masterUrl).text, masterUrl)
    } catch (e: Exception) {
        emptyList()
    }
}
