package com.lagradost

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal data class CikavaSubtitle(
    val language: String,
    val url: String,
)

internal data class CikavaPlayerData(
    val streamUrl: String,
    val subtitle: CikavaSubtitle?,
)

private val cikavaFileRegex = "file\\s*:\\s*['\"]([^'\"]+)['\"]".toRegex()
private val cikavaSubtitleRegex = "subtitle\\s*:\\s*['\"]([^'\"]*)['\"]".toRegex()

internal fun parseCikavaPlayerData(script: String): CikavaPlayerData {
    val streamUrl = cikavaFileRegex.find(script)?.groupValues?.getOrNull(1).orEmpty().trim()
    val subtitle = cikavaSubtitleRegex.find(script)?.groupValues?.getOrNull(1)
        ?.let(::parseCikavaSubtitle)
    return CikavaPlayerData(streamUrl, subtitle)
}

internal fun parseCikavaSubtitle(raw: String): CikavaSubtitle? {
    val value = raw.trim()
    if (!value.startsWith("[")) return null

    val endIndex = value.indexOf(']')
    if (endIndex <= 1) return null

    val language = value.substring(1, endIndex).trim()
    val url = value.substring(endIndex + 1).trim().trimEnd(',')
    if (language.isBlank() || !url.startsWith("http://") &&
        !url.startsWith("https://") && !url.startsWith("//")
    ) {
        return null
    }
    return CikavaSubtitle(language, url)
}

/**
 * Визначає недоступний матеріал за маркером каталогу або повідомленням про видалення.
 * Такі записи не повинні потрапляти у пошук чи каталоги.
 */
internal fun isCikavaDeleted(item: Element): Boolean {
    val qualityMarker = item.select(".fquality").text()
    val itemText = item.text()
    return qualityMarker.contains("ВИДАЛЕНО", ignoreCase = true) ||
        itemText.contains("Озвучення ставимо на пауз", ignoreCase = true) ||
        itemText.contains("Видалено на прохання правовласника", ignoreCase = true)
}

/** Витягує trailer iframe за індексом вкладки «Трейлер». */
internal fun extractCikavaTrailer(document: Document): String? {
    val trailerIndex = document
        .select(".tabs-sel span")
        .indexOfFirst { it.text().contains("трейлер", ignoreCase = true) }
    if (trailerIndex < 0) return null

    val iframe = document
        .select(".tabs-b.video-box")
        .getOrNull(trailerIndex)
        ?.selectFirst("iframe[src], iframe[data-src], video[src], source[src]")
        ?: return null
    val rawUrl = iframe.attr("src").ifBlank { iframe.attr("data-src") }.trim()
    return when {
        rawUrl.startsWith("//") -> "https:$rawUrl"
        rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
        else -> null
    }
}
