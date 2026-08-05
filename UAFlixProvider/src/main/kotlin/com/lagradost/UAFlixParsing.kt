package com.lagradost

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
