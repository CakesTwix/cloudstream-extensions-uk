package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.models.PlayerJson
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class KinoVezhaProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://kinovezha.tv"
    override var name = "KinoVezha"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.Cartoon,
        TvType.TvSeries
    )

    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/films/page/" to "Фільми",
        "$mainUrl/series/page/" to "Серіали",
        "$mainUrl/cartoons/page/" to "Мультфільми",
        "$mainUrl/s-cartoons/page/" to "Мультсеріали",
    )

    val fileRegex = "file\\s*:\\s*[\"']([^\",']+?)[\"']".toRegex()

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document

        val home = document.select(".movie-item").map {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val title = this.select(".movie-item__title").text()
        val href = this.select(".movie-item__link").attr("href").toString()
        val posterUrl = mainUrl + this.select(".img-fit-cover img").attr("data-src")

        return newMovieSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }

    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            url = mainUrl,
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to query.replace(" ", "+")
            )
        ).document

        return document.select(".movie-item").map {
            it.toSearchResponse()
        }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Parse info
        val title = document.select(".inner-page__title").text()
        val poster = mainUrl + document.select(".img-fit-cover img").attr("src")
        val tags = document.select(".inner-page__list li a").map { it.text() }
        // Log.d("load-debug", tags.toString())
        val year = document.select(".inner-page__list li")[0].select("a").text().toIntOrNull()

        val tvType = if (isKinoVezhaSeries(tags)) TvType.TvSeries else TvType.Movie
        val description = document.select("div.inner-page__text").text()
        // val author = someInfo.select("strong:contains(Студія:)").next().html()
        val rating = document.selectFirst(".dd-imdb-colours")?.text()

        // Parse episodes
        val episodes = mutableListOf<Episode>()
        val playerUrl = document.select(".video-responsive > iframe").attr("src")
        val trailerUrl = extractKinoVezhaTrailer(document)
        val trailer = if (trailerUrl?.contains("tortuga.tw/vod", ignoreCase = true) == true) {
            val trailerHtml = runCatching {
                app.get(trailerUrl, headers = mapOf("Referer" to mainUrl)).text
            }.getOrDefault("")
            resolveKinoVezhaTrailerUrl(trailerUrl, trailerHtml)
        } else {
            trailerUrl
        }

        // Return to app
        // Parse Episodes as Series
        return if (tvType == TvType.TvSeries) {
            val playerRawJson = Decoder.decodeAndReverse(
                fileRegex.find(
                    app.get(playerUrl, headers = mapOf("Referer" to mainUrl))
                        .document
                        .select("script")
                        .html()
                )?.groups?.get(1)?.value.toString()
            )

            AppUtils.tryParseJson<List<PlayerJson>>(playerRawJson)?.map { season ->
                for (episode in season.folder) {
                    val episodeData = "$playerUrl|${season.title}|${episode.title}"
                    if (episodes.none { it.data == episodeData }) {
                        episodes.add(
                            newEpisode(episodeData) {
                                this.name = episode.title
                                this.season = season.season.toIntOrNull()
                                this.episode = episode.number.toIntOrNull()
                                this.posterUrl = episode.poster
                            }
                        )
                    }
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                trailer?.let {
                    addTrailer(
                        it,
                        referer = if (it.contains(".m3u8", ignoreCase = true)) "https://tortuga.tw/" else null,
                        addRaw = it.contains(".m3u8", ignoreCase = true),
                    )
                }
            }
        } else { // Parse as Movie.
            newMovieLoadResponse(title, url, TvType.Movie, "$playerUrl|${title.replace("|", "")}") {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                trailer?.let {
                    addTrailer(
                        it,
                        referer = if (it.contains(".m3u8", ignoreCase = true)) "https://tortuga.tw/" else null,
                        addRaw = it.contains(".m3u8", ignoreCase = true),
                    )
                }
            }
        }
    }


    // It works when I click to view the series
    override suspend fun loadLinks(
        data: String, // (Serial) [Player Url, Season, Episode] | (Film) [Player Url, Title]
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split("|")

        // Its film, parse one m3u8
        if(dataList.size == 2){
            val m3u8Url = Decoder.decodeAndReverse(
                fileRegex.find(
                    app.get(dataList[0], headers = mapOf("Referer" to mainUrl))
                        .document
                        .select("script")
                        .html()
                )?.groups?.get(1)?.value.toString()
            )
            M3u8Helper.generateM3u8(
                source = dataList[1],
                streamUrl = m3u8Url.toString(),
                referer = "https://tortuga.wtf/"
            ).dropLast(1).forEach(callback)

            return true
        }

        val playerRawJson = Decoder.decodeAndReverse(
            fileRegex.find(
                app.get(dataList[0], headers = mapOf("Referer" to mainUrl))
                    .document
                    .select("script")
                    .html()
            )?.groups?.get(1)?.value.toString()
        )
        AppUtils.tryParseJson<List<PlayerJson>>(playerRawJson)
            ?.filter { it.title == dataList[1] } // Фільтруємо потрібний сезон
            ?.flatMap { it.folder }              // Беремо список епізодів
            ?.filter { it.title == dataList[2] } // Фільтруємо потрібний епізод
            ?.forEach { episode ->               // Обробляємо кожен епізод
                // Старий формат Tortuga додає субтитри до поля file після HLS URL.
                val parsedEpisode = parseKinoVezhaEpisodeFile(episode.file) ?: return@forEach

                M3u8Helper.generateM3u8(
                    source = parsedEpisode.source,
                    streamUrl = parsedEpisode.streamUrl,
                    referer = "https://tortuga.wtf/"
                ).dropLast(1).forEach(callback)

                val subtitle = episode.subtitle ?: parsedEpisode.subtitle
                if (!subtitle.isNullOrBlank()) {
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            subtitle.substringAfterLast("[").substringBefore("]"),
                            subtitle.substringAfter("]")
                        )
                    )
                }
            }
        return true
    }

    object Decoder {

        fun torDecrypt(encoded: String): String {
            if (encoded.isEmpty()) return ""
            try {
                val cleaned = encoded.replace(Regex("[^A-Za-z0-9+/]"), "")
                val pad = cleaned.length % 4
                val cleanEncoded = cleaned + if (pad > 1) "=".repeat(4 - pad) else ""

                val decoded = android.util.Base64.decode(cleanEncoded, android.util.Base64.DEFAULT)
                if (decoded.size < 2) return ""

                val saltChar = decoded[0].toInt() and 0xFF
                val decryptedBytes = ByteArray(decoded.size - 1)

                for (i in 1 until decoded.size) {
                    val f = (saltChar + 7 * (i - 1) + 13) % 256
                    decryptedBytes[i - 1] = (decoded[i].toInt() xor f).toByte()
                }

                return String(decryptedBytes, Charsets.UTF_8)
            } catch (e: Exception) {
                return ""
            }
        }

        /**
         * Декодує рядок із формату Base64.
         * @param encodedString Закодований рядок (Base64).
         * @return Декодований рядок (String) або null у разі помилки.
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun decodeBase64(encodedString: String): String? {
            try {
                return String(Base64.decode(encodedString.replace("==", "")), Charsets.UTF_8)
            } catch (_: Exception) {
                return String(Base64.decode(encodedString.replace("===", "=")), Charsets.UTF_8)
            }
        }

        /**
         * Реверсує (перевертає) вхідний рядок.
         * @param inputString Рядок для реверсування.
         * @return Реверсований рядок.
         */
        fun reverseText(inputString: String): String {
            return inputString.reversed()
        }

        /**
         * Комбінована функція: спочатку декодує Base64, потім реверсує результат.
         * (Це зазвичай використовується, коли обфускація складається з двох етапів).
         * @param encodedString Закодований рядок.
         * @return Реверсований та декодований рядок або null.
         */
        fun decodeAndReverse(encodedString: String): String? {
            val decrypted = torDecrypt(encodedString)
            if (decrypted.startsWith("http") || decrypted.startsWith("[")) {
                return decrypted
            }
            val decoded = decodeBase64(encodedString)
            return decoded?.let {
                reverseText(it)
            }
        }
    }
}

/** Визначає серіали за жанром, включно з формами «Мультсеріал» та «Міні-серіал». */
internal fun isKinoVezhaSeries(tags: List<String>): Boolean =
    tags.any { it.contains("серіал", ignoreCase = true) }

/** Нормалізує старий формат `file` від Tortuga, де субтитри вбудовані після HLS URL. */
internal data class KinoVezhaEpisodeFile(
    val source: String,
    val streamUrl: String,
    val subtitle: String? = null,
)

internal fun parseKinoVezhaEpisodeFile(rawFile: String): KinoVezhaEpisodeFile? {
    val raw = rawFile.trim()
    if (raw.isBlank()) return null

    val source = raw
        .takeIf { it.startsWith("{") }
        ?.substringAfter("{")
        ?.substringBefore("}")
        ?.takeIf { it.isNotBlank() }
        ?: "Цікава Ідея"
    val streamAndSubtitle = if (raw.startsWith("{")) raw.substringAfter("}") else raw
    val subtitleMarker = streamAndSubtitle.indexOf("(subtitle:", ignoreCase = true)
    val streamUrl = if (subtitleMarker >= 0) {
        streamAndSubtitle.substring(0, subtitleMarker)
    } else {
        streamAndSubtitle
    }
    val subtitle = if (subtitleMarker >= 0) {
        streamAndSubtitle
            .substring(subtitleMarker + "(subtitle:".length)
            .removeSuffix(")")
            .trim()
            .takeIf { it.isNotBlank() }
    } else {
        null
    }

    return KinoVezhaEpisodeFile(
        source = source,
        streamUrl = streamUrl.trim().takeIf { it.isNotBlank() } ?: return null,
        subtitle = subtitle,
    )
}

/** Знаходить iframe лише в контенті вкладки, підписаної «Трейлер». */
internal fun extractKinoVezhaTrailer(document: Document): String? {
    val trailerIndex = document
        .select(".tabs-block__select--player span")
        .indexOfFirst { it.text().contains("трейлер", ignoreCase = true) }
    if (trailerIndex < 0) return null

    return document
        .select(".tabs-block__content.video-inside")
        .getOrNull(trailerIndex)
        ?.selectFirst("iframe[src], video[src], source[src]")
        ?.attr("src")
        ?.trim()
        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}

/** Розшифровує сторінку Tortuga-трейлера до прямого HLS URL. */
internal fun resolveKinoVezhaTrailerUrl(
    trailerUrl: String?,
    trailerPageHtml: String,
    decode: (String) -> String? = { KinoVezhaProvider.Decoder.decodeAndReverse(it) },
): String? {
    val normalized = trailerUrl?.trim()?.takeIf { it.startsWith("http") } ?: return null
    if (!normalized.contains("tortuga.tw/vod", ignoreCase = true)) return normalized

    val encodedFile = Regex("file\\s*:\\s*[\\\"']([^\\\",']+?)[\\\"']")
        .find(trailerPageHtml)
        ?.groups
        ?.get(1)
        ?.value
        ?.trim()
        ?: return null

    val decoded = if (encodedFile.startsWith("http", ignoreCase = true)) encodedFile else decode(encodedFile)
    return decoded?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
}
