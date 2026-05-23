package com.lagradost

import android.util.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.PlayerJson
import org.jsoup.nodes.Element
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class SerialnoProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://serialno.tv"
    override var name = "Serialno"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.Cartoon,
        TvType.TvSeries,
        TvType.Anime
    )
    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/series/page/" to "Серіали",
        "$mainUrl/cartoons/page/" to "Мультсеріали",
        "$mainUrl/mini-serials/page/" to "Міні-серіали",

        )

    val fileRegex = "file\\s*:\\s*[\"']([^\",']+?)[\"']".toRegex()

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document

        val home = document.select(".th-item").map {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val title = this.select(".th-title").text()
        val href = this.select(".th-in").attr("href")
        val posterUrl = mainUrl + this.select(".img-fit img").attr("data-src")

        return newMovieSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }

    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            url = "$mainUrl/",
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to query.replace(" ", "+")
            )
        ).document

        return document.select(".th-item").map {
            it.toSearchResponse()
        }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // General info
        val generalInfo = document.select(".flist li")

        // Parse info
        val title = document.select(".full h1").text()
        val poster = document.select(".fposter a").attr("href")

        val tags = mutableListOf<String>()
        // Can be smaller
        if (generalInfo.size > 4) {
            document.select(".flist li")[4].select("a").map { tags.add(it.text()) }
        } else {
            document.select(".flist li")[3].select("a").map { tags.add(it.text()) }
        }
        val year = document.select(".flist li")[1].select("a").text().toIntOrNull()
        val tvType = TvType.TvSeries
        val description = document.select(".full-text").text()
        // val author = someInfo.select("strong:contains(Студія:)").next().html()
        val rating = document.selectFirst(".th-voice")?.text()
        // Parse episodes
        val episodes = mutableListOf<Episode>()
        val playerUrl = document.select("div.video-box iframe").attr("src")

        // Return to app
        // Parse Episodes as Series
        val playerRawJson = Decoder.decodeAndReverse(fileRegex.find(app.get(playerUrl).document.select("script").html())?.groups?.get(1)?.value.toString())
        Log.d("CakesTwix-Debug", playerRawJson.toString())
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
        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.score = Score.from10(rating)
        }
    }


    override suspend fun loadLinks(
        data: String, // (Serial) [Player Url, Season, Episode]
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split("|")
        Log.d("CakesTwix-Debug", data)
        val playerRawJson = Decoder.decodeAndReverse(fileRegex.find(app.get(dataList[0]).document.select("script").html())?.groups?.get(1)?.value ?: "")

        AppUtils.tryParseJson<List<PlayerJson>>(playerRawJson)
            ?.filter { it.title == dataList[1] } // Фільтруємо потрібний сезон
            ?.flatMap { it.folder }              // Беремо список епізодів
            ?.filter { it.title == dataList[2] } // Фільтруємо потрібний епізод
            ?.forEach { episode ->               // Обробляємо кожен епізод
                val dubTitle = if (episode.file.startsWith("{")) episode.file.substringAfter("{").substringBefore("}") else "Цікава Ідея"
                val streamUrl = if (episode.file.startsWith("{")) episode.file.substringAfter("}") else episode.file

                M3u8Helper.generateM3u8(
                    source = dubTitle,
                    streamUrl = streamUrl,
                    referer = "https://tortuga.wtf/"
                ).dropLast(1).forEach(callback)

                if (!episode.subtitle.isNullOrBlank()) {
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            episode.subtitle.substringAfterLast("[").substringBefore("]"),
                            episode.subtitle.substringAfter("]")
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