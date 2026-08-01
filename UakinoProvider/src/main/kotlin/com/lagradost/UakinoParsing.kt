package com.lagradost

internal data class UakinoEpisodeData(
    val requestUrl: String,
    val episodeName: String?,
)

internal fun normalizeUakinoPlayerUrl(rawUrl: String): String = when {
    rawUrl.startsWith("//") -> "https:$rawUrl"
    rawUrl.startsWith("http://") -> "https://${rawUrl.removePrefix("http://")}"
    else -> rawUrl
}

internal fun parseUakinoEpisodeData(data: String): UakinoEpisodeData {
    val separator = data.indexOf(',')
    if (separator < 0) return UakinoEpisodeData(data, null)

    return UakinoEpisodeData(
        requestUrl = data.substring(0, separator),
        episodeName = data.substring(separator + 1),
    )
}

internal fun parseUakinoYear(rawYear: String, fallback: Int): Int =
    rawYear.trim().toIntOrNull() ?: fallback
