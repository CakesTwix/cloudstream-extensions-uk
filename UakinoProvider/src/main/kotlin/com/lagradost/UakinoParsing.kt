package com.lagradost

import org.jsoup.nodes.Document
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal data class UakinoEpisodeData(
    val requestUrl: String,
    val episodeName: String?,
)

internal fun normalizeUakinoPlayerUrl(rawUrl: String): String = when {
    rawUrl.startsWith("//") -> "https:$rawUrl"
    rawUrl.startsWith("http://", ignoreCase = true) -> "https://${rawUrl.substring(7)}"
    else -> rawUrl
}

internal fun normalizeUakinoPlayerUrlOrNull(rawUrl: String?): String? {
    val clean = rawUrl?.trim().orEmpty()
    if (clean.isBlank() || clean.equals("null", ignoreCase = true) || clean.startsWith("javascript:", ignoreCase = true)) return null
    val url = when {
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("http://", ignoreCase = true) -> "https://${clean.substring(7)}"
        clean.startsWith("https://", ignoreCase = true) -> clean
        else -> null
    } ?: return null
    return url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
}

internal data class UakinoMovieData(
    val pageUrl: String,
    val newsId: String?,
    val playerUrls: List<String> = emptyList(),
)

internal fun buildUakinoMovieData(pageUrl: String, newsId: String?, playerUrls: List<String>): String {
    val id = newsId.orEmpty()
    val players = playerUrls.joinToString("|")
    return "MOVIE:$pageUrl#$id#$players"
}

internal fun parseUakinoMovieData(data: String): UakinoMovieData {
    if (!data.startsWith("MOVIE:")) {
        val id = data.split("/").lastOrNull()?.split("-")?.firstOrNull()?.toIntOrNull()?.toString()
        return UakinoMovieData(pageUrl = data.substringBefore(","), newsId = id)
    }
    val parts = data.removePrefix("MOVIE:").split("#")
    val pageUrl = parts.getOrNull(0).orEmpty()
    val newsId = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
    val playerUrls = parts.getOrNull(2)?.split("|")?.filter { it.isNotBlank() }.orEmpty()
    return UakinoMovieData(pageUrl, newsId, playerUrls)
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

internal fun resolveUakinoStreamUrls(rawFile: String): List<String> {
    val value = rawFile.trim()
    if (value.isBlank()) return emptyList()

    val directUrls = Regex("""https?://[^\s,"'\]]+""").findAll(value)
        .map { it.value }
        .filter { it.contains(".m3u8", ignoreCase = true) || it.contains(".mp4", ignoreCase = true) || it.contains("/vod/", ignoreCase = true) }
        .toList()
    if (directUrls.isNotEmpty()) {
        return directUrls
    }

    val decoded = decodeUakinoTortuga(value)
    if (decoded != null) {
        val decodedUrls = Regex("""https?://[^\s,"'\]]+""").findAll(decoded)
            .map { it.value }
            .toList()
        if (decodedUrls.isNotEmpty()) return decodedUrls
        return listOf(decoded)
    }

    if (value.startsWith("http://") || value.startsWith("https://")) {
        return listOf(value)
    }

    return emptyList()
}

internal fun resolveUakinoStreamUrl(rawUrl: String): String? =
    resolveUakinoStreamUrls(rawUrl).firstOrNull()

internal fun extractUakinoMoviePlayerUrls(document: Document): List<String> {
    val trailerUrl = extractUakinoTrailer(document)
    val urls = mutableListOf<String>()

    fun isTrailer(url: String): Boolean {
        if (url.contains("youtube.com", ignoreCase = true) || url.contains("youtu.be", ignoreCase = true)) return true
        if (!trailerUrl.isNullOrBlank() && url.equals(trailerUrl, ignoreCase = true)) return true
        return false
    }

    // 1. Schema.org video metadata: link/meta itemprop="video" or itemprop="embedUrl"
    document.select("[itemprop]")
        .filter { el ->
            el.attr("itemprop").split(Regex("\\s+")).any { prop ->
                prop.equals("video", ignoreCase = true) ||
                prop.equals("embedUrl", ignoreCase = true) ||
                prop.equals("contentUrl", ignoreCase = true)
            }
        }
        .flatMap { el -> listOf("value", "content", "href", "src", "data-src").map { el.attr(it) } }
        .mapNotNull(::normalizeUakinoPlayerUrlOrNull)
        .filterNot(::isTrailer)
        .forEach { urls.add(it) }

    // 2. Candidate iframes (ignoring trailer/overroll containers and youtube)
    document.select("iframe[src], iframe[data-src]").forEach { iframe ->
        val parentTrailer = iframe.parents().any { p ->
            p.id().contains("trailer", ignoreCase = true) ||
            p.id().contains("overroll", ignoreCase = true) ||
            p.className().contains("trailer", ignoreCase = true)
        }
        if (parentTrailer) return@forEach

        val raw = iframe.attr("src").ifBlank { iframe.attr("data-src") }
        val normalized = normalizeUakinoPlayerUrlOrNull(raw) ?: return@forEach
        if (!isTrailer(normalized)) {
            urls.add(normalized)
        }
    }

    return urls.distinct()
}

