package com.lagradost

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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

internal fun resolveUakinoDetailUrl(
    originalData: String,
    targetEpisode: String?,
    requestUrl: String,
): String = if (targetEpisode == null) originalData else requestUrl

internal fun parseUakinoYear(rawYear: String, fallback: Int): Int =
    rawYear.trim().toIntOrNull() ?: fallback

/**
 * Розшифровує `file` з Tortuga-плеєра.
 * Перший байт є сіллю, решта байтів XOR-яться з (salt + 7*i + 13).
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun decodeUakinoTortuga(encoded: String): String? {
    val clean = encoded.trim().replace(Regex("\\s"), "").trimEnd('=')
    if (clean.isBlank()) return null

    return try {
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        val decoded = Base64.decode(padded)
        if (decoded.size < 2) return null

        val salt = decoded[0].toInt() and 0xFF
        val result = ByteArray(decoded.size - 1)
        for (i in 1 until decoded.size) {
            val key = (salt + 7 * (i - 1) + 13) % 256
            result[i - 1] = ((decoded[i].toInt() and 0xFF) xor key).toByte()
        }

        String(result, Charsets.UTF_8).takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        }
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun resolveUakinoStreamUrl(rawUrl: String): String? {
    val value = rawUrl.trim()
    if (value.isBlank()) return null
    if (value.startsWith("http://") || value.startsWith("https://")) return value
    return decodeUakinoTortuga(value)
}
