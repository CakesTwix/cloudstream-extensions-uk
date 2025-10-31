package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.models.PlayerJson
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Element
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
        "$mainUrl/series/page/" to "Серіали",
        "$mainUrl/films/page/" to "Фільми",
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
        val tags = document.select(".inner-page__list li")[2].select("a").map { it.text() }
        // Log.d("load-debug", tags.toString())
        val year = document.select(".inner-page__list li")[0].select("a").text().toIntOrNull()

        val tvType = if(tags.contains("Мультсеріали") or tags.contains("Серіали")) TvType.TvSeries else TvType.Movie
        val description = document.select("div.inner-page__text").text()
        // val author = someInfo.select("strong:contains(Студія:)").next().html()
        val rating = document.selectFirst(".dd-imdb-colours")?.text()

        // Parse episodes
        val episodes = mutableListOf<Episode>()
        val playerUrl = document.select(".video-responsive > iframe").attr("src")

        // Return to app
        // Parse Episodes as Series
        return if (tvType == TvType.TvSeries) {
            val playerRawJson = Decoder.decodeAndReverse(fileRegex.find(app.get(playerUrl).document.select("script[type=text/javascript]").html())?.groups?.get(1)?.value.toString())

            AppUtils.tryParseJson<List<PlayerJson>>(playerRawJson)?.map { season -> // Dubs
                for (episode in season.folder) {                                     // Seasons
                    for (dubs in episode.folder) {                              // Episodes
                        episodes.add(
                            newEpisode("${season.title}, ${episode.title}, $playerUrl") {
                                this.name = episode.title
                                this.season = season.season
                                this.episode = episode.number
                                this.posterUrl = dubs.poster
                                this.data = "${season.title}, ${episode.title}, $playerUrl"
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
            }
        } else { // Parse as Movie.
            newMovieLoadResponse(title, url, TvType.Movie, "$title, $playerUrl") {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
            }
        }
    }


    // It works when I click to view the series
    override suspend fun loadLinks(
        data: String, // (Serisl) [Season, Episode, Player Url] | (Film) [Title, Player Url]
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split(", ")

        // Its film, parse one m3u8
        if(dataList.size == 2){
            val m3u8Url = Decoder.decodeAndReverse(fileRegex.find(app.get(dataList[1]).document.select("script[type=text/javascript]").html())?.groups?.get(1)?.value.toString())
            M3u8Helper.generateM3u8(
                source = dataList[0],
                streamUrl = m3u8Url.toString(),
                referer = "https://tortuga.wtf/"
            ).last().let(callback)

            return true
        }

        val playerRawJson = Decoder.decodeAndReverse(fileRegex.find(app.get(dataList[2]).document.select("script[type=text/javascript]").html())?.groups?.get(1)?.value.toString())
        AppUtils.tryParseJson<List<PlayerJson>>(playerRawJson)
            ?.filter { it.title == dataList[0] } // Фільтруємо потрібний сезон
            ?.flatMap { it.folder }              // Беремо список епізодів
            ?.filter { it.title == dataList[1] } // Фільтруємо потрібний епізод
            ?.flatMap { it.folder }              // Беремо список дубляжів
            ?.forEach { dubs ->                  // Обробляємо кожен дубляж
                M3u8Helper.generateM3u8(
                    source = dubs.title,
                    streamUrl = dubs.file,
                    referer = "https://tortuga.wtf/"
                ).last().let(callback)

                if (!dubs.subtitle.isNullOrBlank()) {
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            dubs.subtitle.substringAfterLast("[").substringBefore("]"),
                            dubs.subtitle.substringAfter("]")
                        )
                    )
                }
            }
        return true
    }

    object Decoder {

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
            val decoded = decodeBase64(encodedString)
            return decoded?.let {
                reverseText(it)
            }
        }
    }
}