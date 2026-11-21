package com.lagradost.models

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink

data class TimedValue<T>(
    val value: T,
    val at: Long = System.currentTimeMillis()
) {
    fun isFresh(ttlMs: Long): Boolean = System.currentTimeMillis() - at < ttlMs
}

data class PosterFetchTask(
    val moonIds: List<Int>,
    val otherIds: List<Int>,
    val apiPosters: List<String>,
    val animeId: Int,
    val epNum: Int
)

data class CollectedEpisodes(
    val translationName: String,
    val playerName: String,
    val episodes: List<FundubEpisode>
)

data class SubToAdd(
    val lang: String,
    val url: String,
    val priority: Int
)

data class FenixStream(
    val url: String,
    val quality: Int,
    val codecs: String?
)

data class ResolvedEpisode(
    val links: List<ExtractorLink>,
    val subtitles: List<SubtitleFile>,
    val resolvedAt: Long = System.currentTimeMillis()
) {
    fun isExpired(): Boolean {
        val now = System.currentTimeMillis() / 1000
        return links.any { l ->
            expiresRegex.find(l.url)
                ?.groupValues?.get(1)?.toLongOrNull()
                ?.let { exp -> exp - 30 <= now } ?: false
        }
    }

    fun isUsable(maxAgeMs: Long = Long.MAX_VALUE): Boolean {
        return links.isNotEmpty() && !isExpired() &&
            System.currentTimeMillis() - resolvedAt < maxAgeMs
    }

    companion object {
        private val expiresRegex = Regex("""[?&]expires=(\d+)""")
    }
}