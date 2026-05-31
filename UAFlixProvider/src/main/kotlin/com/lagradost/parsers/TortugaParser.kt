package com.lagradost.parsers

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

object TortugaParser {

    suspend fun extractVod(
        scriptHtml: String,
        source: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileUrl = scriptHtml
            .substringAfterLast("file:\"")
            .substringBefore("\",")

        M3u8Helper.generateM3u8(
            source = source,
            streamUrl = fileUrl,
            referer = "https://tortuga.wtf/"
        ).dropLast(1).forEach(callback)
    }

    suspend fun extractPlaylist(
        scriptHtml: String,
        source: String,
        streamUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        M3u8Helper.generateM3u8(
            source = source,
            streamUrl = streamUrl,
            referer = "https://tortuga.wtf/"
        ).dropLast(1).forEach(callback)
    }
}
