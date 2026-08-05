package com.lagradost

import org.jsoup.nodes.Document

internal data class UAFlixSubtitle(
    val language: String,
    val url: String,
)

internal fun parseUAFlixSubtitle(raw: String?): UAFlixSubtitle? {
    val value = raw?.trim().orEmpty()
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
    return UAFlixSubtitle(language, url)
}

/** Витягує трейлер лише з окремої кнопки UAFlix, а не з основного плеєра. */
internal fun extractUAFlixTrailer(document: Document): String? {
    val trailerButton = document.select(".to-trailer").firstOrNull { element ->
        element.attr("data-src").isNotBlank() ||
            element.select("[data-src], iframe[src], iframe[data-src], a[href]").isNotEmpty()
    } ?: return null

    val rawUrl = sequence {
        yield(trailerButton.attr("data-src"))
        yieldAll(trailerButton.select("[data-src]").map { it.attr("data-src") })
        yieldAll(trailerButton.select("iframe[src], iframe[data-src]").map {
            it.attr("src").ifBlank { it.attr("data-src") }
        })
        yieldAll(trailerButton.select("a[href]").map { it.attr("href") })
    }.map { it.trim() }.firstOrNull { it.isNotBlank() }

    return when {
        rawUrl.isNullOrBlank() -> null
        rawUrl.startsWith("//") -> "https:$rawUrl"
        rawUrl.startsWith("http://", ignoreCase = true) ||
            rawUrl.startsWith("https://", ignoreCase = true) -> rawUrl
        else -> null
    }
}
