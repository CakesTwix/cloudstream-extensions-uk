package com.lagradost

import com.google.gson.Gson
import com.google.gson.JsonArray
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
    private val otherDataSelector = "div.bslide__desc > ul.bslide__text"

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

        val title = getPageTitle(document)
        val engTitle = getPageEngTitle(document)
        val posterUrl = getPagePosterUrl(document)
        val year = getYear(document)
        val description = getDescription(document)
        val rating = getRating(document)
        val duration = getDuration(document)
        val trailerUrl = getTrailerUrL(document)
        val tags = getTags(document)
        val actors = getActors(document)


//        val playerRawJson = app.get(playerUrl, referer = mainUrl).document
        return when (val tvType = getTvType(url)) {
            TvType.Movie, TvType.Cartoon, TvType.AnimeMovie -> {//videos with 1 episode
                newMovieLoadResponse(title, url, tvType, url) {
                    this.posterUrl = posterUrl
                    this.plot = description
                    this.tags = tags
                    this.year = year
                    this.rating = rating
                    this.name = engTitle
                    addActors(actors)
                    addTrailer(trailerUrl)
                    this.duration = duration
                }
            }

            else -> { //videos with multiple episodes
                val episodes = getEpisodes(document)

                newTvSeriesLoadResponse(title, url, tvType, episodes) {
                    this.posterUrl = posterUrl
                    this.plot = description
                    this.tags = tags
                    this.year = year
                    this.rating = rating
                    this.name = engTitle
                    addActors(actors)
                    addTrailer(trailerUrl)
                    this.duration = duration
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
                val m3uUrl = app.get(sourceUrl).document.select("iframe").attr("src")

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

    private fun getEpisodes(document: Document): List<Episode> {
        TODO("Not yet implemented")
//        return listOf<Episode>()
    }

    private fun getDuration(document: Document): Int {
        val text = document.select(otherDataSelector).select("li").first {
            it.text().contains("Тривалість:")
        }.text()

        val regex = Regex("""(\d+) год (\d+) хв""")
        val match = regex.find(text)
        if (match != null) {
            val (hours, minutes) = match.destructured
            return (hours.toInt() * 60 + minutes.toInt())
        }
        return 0
    }


    private fun getRating(document: Document) =
        document.select("div.pmovie__rating-content > a")[0].text().toRatingInt()


    private fun getDescription(document: Document) =
        document.select("div.page__text").text()

    private fun getActors(document: Document): List<String> {
        return document.select("ul.pmovie__list").select("li")
            .first { it.text().contains("Актори:") }.select("a")
            .map { it.text() }.toList()
    }

    private fun getTags(document: Document): List<String> {
        return document.select(otherDataSelector).select("li")
            .first { element -> element.text().contains("Жанр:") }.select("a")
            .map { it.text() }.toList()
    }

    private fun getYear(document: Document): Int {
        val yearIndexTag = "Рік виходу:"
        return document.select(otherDataSelector).select("li")
            .first { it.select("span").text() == yearIndexTag }.text()
            .replace(yearIndexTag, "").trim().toInt()
    }

    private fun getPagePosterUrl(document: Document) =
        fixUrl(document.select("div.bslide__poster > a > img").attr("src"))

    private fun getPageEngTitle(document: Document) = document.select("div.bslide__subtitle").text()

    private fun getPageTitle(document: Document) = document.select("h1.bslide__title").text()

    private fun getTvType(url: String): TvType {
        return when {
            url.contains("serie") -> TvType.TvSeries
            url.contains("cartoon/series") -> TvType.TvSeries
            url.contains("cartoon") -> TvType.Cartoon
            url.contains("anime") -> TvType.Anime
            else -> TvType.Movie
        }
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
            } else {
                substringAfter
            }
        } else {
            ""
        }
    }
}