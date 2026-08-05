package com.lagradost

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
