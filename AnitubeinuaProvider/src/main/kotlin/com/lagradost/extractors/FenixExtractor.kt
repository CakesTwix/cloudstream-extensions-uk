package com.lagradost.extractors

import com.lagradost.AnitubeSubtitle
import com.lagradost.DEFAULT_SUBTITLE_LABEL
import com.lagradost.distinctSubtitles
import com.lagradost.extractJsObject
import com.lagradost.markForcedSubtitle
import com.lagradost.models.FenixEpisode
import com.lagradost.models.FenixMediaAccess
import com.lagradost.models.FenixPlaylist
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

/**
 * Витягує відео та субтитри з плеєра FENIX (fenixplay.xyz).
 *
 * На відміну від ashdi/moon, FENIX тримає весь конфіг у `window.FENIX_PLAYLIST`,
 * включно з масивом `subtitles` — саме там лежать форсовані субтитри (написи),
 * які сайт показує у браузері.
 *
 * Усі посилання на media.fenixplay.xyz потребують токена з `window.FENIX_MEDIA_ACCESS`
 * (параметр `?token=`), інакше домен відповідає 403.
 */
class FenixExtractor {

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:126.0) Gecko/20100101 Firefox/126.0"
    private val fenixReferer = "https://fenixplay.xyz/"

    /**
     * Повертає знайдені субтитри — провайдер віддає їх у subtitleCallback сам,
     * бо newSubtitleFile() є suspend-функцією.
     */
    suspend fun getUrl(
        iframeUrl: String,
        sourceName: String,
        episodeNumber: Int?,
        callback: (ExtractorLink) -> Unit,
    ): List<AnitubeSubtitle> {
        val html = try {
            app.get(
                iframeUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Referer" to "https://anitube.in.ua/",
                ),
                cacheTime = 0,
            ).text
        } catch (e: Exception) {
            return emptyList()
        }

        if (html.isBlank()) return emptyList()

        val playlist = extractJsObject(html, "window.FENIX_PLAYLIST")
            ?.let { tryParseJson<FenixPlaylist>(it) }
            ?: return emptyList()

        val mediaAccess = extractJsObject(html, "window.FENIX_MEDIA_ACCESS")
            ?.let { tryParseJson<FenixMediaAccess>(it) }

        val episode = selectEpisode(playlist, episodeNumber) ?: return emptyList()

        // Відео: кожна озвучка — окремий HLS master, плюс загальний hls_master_url
        val streams = buildList {
            episode.tracks.orEmpty().forEach { track ->
                val url = authorize(track.hlsUrl, mediaAccess) ?: return@forEach
                add(url to (track.label?.takeIf { it.isNotBlank() } ?: sourceName))
            }
            authorize(episode.hlsMasterUrl, mediaAccess)?.let { add(it to sourceName) }
        }.distinctBy { it.first }

        streams.forEach { (streamUrl, label) ->
            try {
                M3u8Helper.generateM3u8(
                    source = if (label == sourceName) sourceName else "$sourceName — $label",
                    streamUrl = streamUrl,
                    referer = fenixReferer,
                    headers = mapOf("User-Agent" to userAgent),
                ).forEach(callback)
            } catch (e: Exception) {
                // Якщо конкретна озвучка недоступна — лишаємо решту
            }
        }

        return collectSubtitles(episode, mediaAccess)
    }

    /** Токен vod-посилання прив'язаний до епізоду, тож зазвичай у конфігу лише він один. */
    private fun selectEpisode(playlist: FenixPlaylist, episodeNumber: Int?): FenixEpisode? {
        val episodes = playlist.episodes.orEmpty() + playlist.miniEpisodes.orEmpty()
        if (episodes.isEmpty()) return null
        return episodes.firstOrNull { it.number != null && it.number == episodeNumber }
            ?: episodes.first()
    }

    private fun collectSubtitles(
        episode: FenixEpisode,
        mediaAccess: FenixMediaAccess?,
    ): List<AnitubeSubtitle> =
        episode.subtitles.orEmpty().mapNotNull { subtitle ->
            val url = authorize(subtitle.url, mediaAccess) ?: return@mapNotNull null
            val name = subtitle.label?.takeIf { it.isNotBlank() }
                ?: subtitle.language?.takeIf { it.isNotBlank() }
                ?: DEFAULT_SUBTITLE_LABEL
            // kind == "signs" — це написи, тобто той самий форсований трек
            val isForced = subtitle.isForced == true || subtitle.kind.equals("signs", true)
            AnitubeSubtitle(markForcedSubtitle(name, isForced), url)
        }.distinctSubtitles()

    /** Додає `?token=` до посилань на медіа-домен FENIX. */
    private fun authorize(url: String?, mediaAccess: FenixMediaAccess?): String? {
        val value = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val origin = mediaAccess?.origin?.trim()?.takeIf { it.isNotBlank() } ?: return value
        val token = mediaAccess.token?.trim()?.takeIf { it.isNotBlank() } ?: return value

        if (!value.startsWith(origin, ignoreCase = true)) return value
        if (Regex("[?&]token=").containsMatchIn(value)) return value

        return if (value.contains('?')) "$value&token=$token" else "$value?token=$token"
    }
}
