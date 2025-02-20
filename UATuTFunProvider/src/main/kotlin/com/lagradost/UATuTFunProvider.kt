package com.lagradost

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

class UATuTFunProvider : MainAPI() {

    private val movieSelector = "#dle-content"
    private val titleSelector = "div.poster__desc > h3 > a > span"
    private val videoUrlSelector = "data-url"
    private val posterUrlSelector =
        "div.poster__img.img-responsive.img-responsive--portrait.img-fit-cover.anim > img"
    private val searchMovieSelector = "div.poster.grid-item"

    // Basic Info
    override var mainUrl = "https://uk.uatut.fun"
    override var name = "UATuT"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Cartoon,
        TvType.Movie,
        TvType.Anime
    )

    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/serie/page/" to "Серіали",
        "$mainUrl/cartoon/series/page/" to "Мультсеріали",
        "$mainUrl/cartoon/page/" to "Мультфільми",
        "$mainUrl/anime/page/" to "Аніме",
        "$mainUrl/film/page/" to "Фільми"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val first = document.select(movieSelector)
            .first() ?: throw ErrorLoadingException("Can't find main page")

        val mainPage = first.children().map {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, mainPage)
    }

    private fun Element.toSearchResponse(): MovieSearchResponse {
        val title = this.select(titleSelector).text()
        val url = this.attr(videoUrlSelector)
        val posterUrl = fixUrl(
            this.select(posterUrlSelector)
                .attr("data-src")
        )

        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response =
            app.post("$mainUrl/index.php?do=search&subaction=search&story=$query").document
        return response.select(searchMovieSelector).map {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.select("h1.bslide__title").text()
        val engTitle = document.select("div.bslide__subtitle").text()
        val posterUrl = fixUrl(document.select("div.bslide__poster > a > img").attr("src"))

        val tags = mutableListOf<String>()
        val actors = mutableListOf<String>()
        val yearIndexTag = "Рік виходу:"
        val otherData = document.select("div.bslide__desc > ul.bslide__text")
        val year = otherData.select("li").first { it.select("span").text() == yearIndexTag }.text()
            .replace(yearIndexTag, "").trim().toInt()

        otherData.select("li").first { element -> element.text().contains("Жанр:") }.select("a")
            .map { tags.add(it.text()) }


        val actorsBlock = document.select("ul.pmovie__list")
        actorsBlock.select("li").first { it.text().contains("Актори:") }.select("a")
            .map { actors.add(it.text()) }

        val tvType = getTvType(url)

        val description = document.select("div.page__text").text()
        val rating = document.select("div.pmovie__rating-content > a")[0].text().toRatingInt()
        val duration = otherData.select("li").first { it.text().contains("Тривалість:") }
        val durationInMinutes = getDurationInMinutes(duration.text())


//        val episodes = mutableListOf<Episode>()


//        val playerRawJson = app.get(playerUrl, referer = mainUrl).document
        return when (tvType) {
            TvType.Movie, TvType.Cartoon, TvType.AnimeMovie -> {//videos with 1 episode
                newMovieLoadResponse(title, url, tvType, url) {
                    this.posterUrl = posterUrl
                    this.plot = description
                    this.tags = tags
                    this.year = year
                    this.rating = rating
                    this.name = engTitle
                    addActors(actors)
                    addTrailer(getTrailerUrL(document))
                    this.duration = durationInMinutes
                }
            }

            else -> { //videos with multiple episodes
                //todo: finish implementing this
                newTvSeriesLoadResponse(title, url, tvType, listOf<Episode>()) {
                    this.posterUrl = posterUrl
                    this.plot = description
                    this.tags = tags
                    this.year = year
                    this.rating = rating
                    this.name = engTitle
                    addActors(actors)
//                    addTrailer()
//                    addDuration()
                }
            }
        }

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val tvType = getTvType(data)

        return when (tvType) {
            TvType.Movie, TvType.Cartoon -> {//movie
                val sourceUrl = fixUrl(document.select("iframe").attr("data-src"))
                var m3uUrl = app.get(sourceUrl).document.select("iframe").attr("src")
                Log.d("DEBUG UATUT loadLinks", m3uUrl)

                if (m3uUrl.substringAfterLast('.') == "txt") {
                    val url = withContext(Dispatchers.IO) {
                        val substringAfterLast = m3uUrl.substringAfterLast("file=")
                        URLDecoder.decode(substringAfterLast, "UTF-8")
                    }

                    val m3u8 = app.get(url)
                    val jsonArray = Gson().fromJson(m3u8.text, JsonArray::class.java)
                    val m3uFileUrl = jsonArray.firstOrNull()?.asJsonObject?.get("file")

                    val m3u8DirectFileUrl = m3uFileUrl.toString().replace("\"", "")
                    M3u8Helper.generateM3u8(
                        source = "uatut",
                        streamUrl = m3u8DirectFileUrl,
                        referer = "https://uk.uatut.fun/"
                    ).last().let(callback)
                }

                true
            }

            else -> {//series

                false
            }
        }
    }

    private fun getTvType(url: String): TvType {
        return when {
            url.contains("serie") -> TvType.TvSeries
            url.contains("cartoon/series") -> TvType.TvSeries
            url.contains("cartoon") -> TvType.Cartoon
            url.contains("anime") -> TvType.Anime
            else -> TvType.Movie
        }
    }

    private fun getDurationInMinutes(text: String): Int {
        val regex = Regex("""(\d+) год (\d+) хв""")
        val match = regex.find(text)
        if (match != null) {
            val (hours, minutes) = match.destructured
            return (hours.toInt() * 60 + minutes.toInt())
        }
        return 0
    }

    private fun getTrailerUrL(document: Document): String {
        val listOfPlayers = document.select("div.tabs-block__select span").map { it.text() }
        val playersUrl = document.select("div.tabs-block__content.video-inside").map {
            val ifrmame = it.select("iframe")
            ifrmame.attr("src").ifEmpty { ifrmame.attr("data-src") }
        }

        val trailerIndex = listOfPlayers.indexOf("Трейлер")
        return if (trailerIndex != -1) {
            val rawUrl = playersUrl[trailerIndex]
            val delimiter = "https://www.youtube.com/"
//            https://www.youtube.com/embed/Gj4cdX01Gb4
//            https://www.youtube.com/watch?v=Gj4cdX01Gb4
            val substringAfter = delimiter + rawUrl.substringAfter(delimiter)
            if (substringAfter.contains("embed")) {
                substringAfter.replace("embed/", "watch?v=")
            }
            substringAfter
        } else {
            ""
        }
    }
}