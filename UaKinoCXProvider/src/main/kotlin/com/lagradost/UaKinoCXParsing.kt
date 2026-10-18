package com.lagradost

import com.lagradost.models.PlayerJson
import org.jsoup.nodes.Document

internal data class UaKinoCXSubtitle(
    val language: String,
    val url: String,
)

internal fun parseUaKinoCXSubtitle(raw: String?): UaKinoCXSubtitle? {
    val clean = raw?.trim().orEmpty()
    if (clean.isBlank()) return null
    val start = clean.indexOf('[')
    val end = clean.indexOf(']')
    if (start >= 0 && end > start) {
        val lang = clean.substring(start + 1, end).trim()
        val url = clean.substring(end + 1).trim().removeSuffix(",")
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return UaKinoCXSubtitle(lang, url)
        }
    } else if (clean.startsWith("http://") || clean.startsWith("https://")) {
        return UaKinoCXSubtitle("Українські", clean.removeSuffix(","))
    }
    return null
}

internal fun extractUaKinoCXTrailer(document: Document): String? {
    val trailerElement = document.selectFirst("#trailer-block iframe, .trailer iframe")
        ?: document.select("iframe").firstOrNull { iframe ->
            val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }
            src.contains("youtube.com", ignoreCase = true) || src.contains("youtu.be", ignoreCase = true)
        }
    return trailerElement?.let {
        val src = it.attr("data-src").ifBlank { it.attr("src") }
        if (src.startsWith("//")) "https:$src" else src
    }
}

internal fun extractUaKinoCXPlayerUrls(document: Document): List<String> {
    return document.select(".page__player iframe, .tabs-block__content iframe, .video-inside iframe")
        .filterNot { iframe ->
            val parent = iframe.parent()
            parent?.id()?.contains("trailer", ignoreCase = true) == true ||
            parent?.className()?.contains("trailer", ignoreCase = true) == true
        }
        .map { it.attr("data-src").ifBlank { it.attr("src") } }
        .filter { it.isNotBlank() && !it.contains("youtube.com", ignoreCase = true) && !it.contains("youtu.be", ignoreCase = true) }
        .map { if (it.startsWith("//")) "https:$it" else it }
        .distinct()
}

internal fun extractOrtifiedHlsUrl(scriptHtml: String): String? {
    val regex = Regex("""hls\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
    return regex.find(scriptHtml)?.groupValues?.get(1)
}

internal fun extractHdvbuaFile(scriptHtml: String): String? {
    val singleQuoteRegex = Regex("""file\s*:\s*'([^']+)'""")
    val doubleQuoteRegex = Regex("""file\s*:\s*"([^"]+)"""")
    return singleQuoteRegex.find(scriptHtml)?.groupValues?.get(1)
        ?: doubleQuoteRegex.find(scriptHtml)?.groupValues?.get(1)
}

internal fun parseEpisodeNumbers(seasonTitle: String, episodeTitle: String): Pair<Int?, Int?> {
    val numberRegex = Regex("""\d+""")
    val s = numberRegex.find(seasonTitle)?.value?.toIntOrNull()
    val ep = numberRegex.find(episodeTitle)?.value?.toIntOrNull()
    return s to ep
}

internal fun isUaKinoCXComingSoon(hasPlayableMedia: Boolean): Boolean {
    return !hasPlayableMedia
}

internal data class UaKinoCXEpisodeInfo(
    val seasonName: String,
    val seasonNumber: Int?,
    val episodeName: String,
    val episodeNumber: Int?,
    val dubName: String?,
    val file: String,
    val poster: String?,
    val subtitle: String?,
)

internal fun flattenPlayerJson(items: List<PlayerJson>): List<UaKinoCXEpisodeInfo> {
    val result = mutableListOf<UaKinoCXEpisodeInfo>()

    fun traverse(
        item: PlayerJson,
        currentSeason: String?,
        currentDub: String?,
    ) {
        val title = item.title.orEmpty().trim()
        val isSeason = title.contains("сезон", ignoreCase = true)
        val isEpisode = title.contains("сері", ignoreCase = true) ||
            title.contains("епізод", ignoreCase = true) ||
            !item.file.isNullOrBlank()

        val season = if (isSeason) title else currentSeason ?: "1 сезон"
        val dub = if (!isSeason && !isEpisode && item.folder != null) title else currentDub

        if (!item.file.isNullOrBlank()) {
            val (sNum, epNum) = parseEpisodeNumbers(season, title)
            result.add(
                UaKinoCXEpisodeInfo(
                    seasonName = season,
                    seasonNumber = sNum,
                    episodeName = title,
                    episodeNumber = epNum,
                    dubName = dub,
                    file = item.file,
                    poster = item.poster,
                    subtitle = item.subtitle,
                )
            )
        }

        item.folder.orEmpty().forEach { child ->
            traverse(child, season, dub)
        }
    }

    items.forEach { traverse(it, null, null) }
    return result
}
