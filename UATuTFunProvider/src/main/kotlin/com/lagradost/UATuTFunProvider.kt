package com.lagradost

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
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
import com.lagradost.model.Season
import com.lagradost.model.SeriesJsonDataModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.text.SimpleDateFormat

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
        "$mainUrl/page/" to "Новинки",
        "$mainUrl/film/page/" to "Фільми",
        "$mainUrl/serie/page/" to "Серіали",
        "$mainUrl/cartoon/series/page/" to "Мультсеріали",
        "$mainUrl/cartoon/page/" to "Мультфільми",
        "$mainUrl/anime/page/" to "Аніме"
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
        Log.d("DEBUG load", "Url: $url")
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
            TvType.Movie, TvType.Cartoon -> {//videos with 1 episode
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
        Log.d("DEBUG loadLinks", "Data: $data")
        val tvType = if (data.startsWith("http")) {
            TvType.Movie
        } else {
            TvType.TvSeries
        }

        return when (tvType) {
            TvType.Movie -> {//movie cartoon anime
                val document = app.get(data).document
                val m3uUrl = getM3uUrl(document)


                if (m3uUrl.endsWith(".m3u8")) {
                    //todo add quality
                    M3u8Helper.generateM3u8(
                        source = "uatut",
                        streamUrl = m3uUrl,
                        referer = "https://uk.uatut.fun/"
                    ).last().let(callback)
                } else {
                    val m3u8 = app.get(m3uUrl)
                    val jsonArray = Gson().fromJson(
                        m3u8.text,
                        JsonArray::class.java
                    )
                    val m3uFileUrl = jsonArray.firstOrNull()?.asJsonObject?.get("file")

                    val m3u8DirectFileUrl = m3uFileUrl.toString().replace("\"", "")
                    //todo add quality
                    M3u8Helper.generateM3u8(
                        source = "uatut",
                        streamUrl = m3u8DirectFileUrl,
                        referer = "https://uk.uatut.fun/"
                    ).forEach(callback)
                }

                true
            }

            else -> {//series
                val (episodeName, episodeSeasonName, seriesUrl) = data.split(";")
                Log.d(
                    "DEBUG loadLinks",
                    "EpisodeName: $episodeName, SeasonName: $episodeSeasonName, SeriesUrl: $seriesUrl"
                )

                val jsonDataModel =
                    getSeriesJsonDataModelByEpisodeName(episodeName, episodeSeasonName, seriesUrl)
                val sourceDubName = jsonDataModel.first().seriesDubName
                val m3u8DirectFileUrl = jsonDataModel.first().seasons.first().episodes.first().file
                M3u8Helper.generateM3u8(
                    source = sourceDubName,
                    streamUrl = m3u8DirectFileUrl,
                    referer = "https://uk.uatut.fun/"
                ).forEach(callback)
                true
            }
        }
    }


    private suspend fun getSeriesJsonDataModel(
        seriesUrl: String,
    ): List<SeriesJsonDataModel> {
        val document = app.get(seriesUrl).document

        val m3uUrl = getM3uUrl(document)
        val itemType = object : TypeToken<List<SeriesJsonDataModel>>() {}.type

        val text = if (m3uUrl.startsWith("http")) {
            app.get(m3uUrl).text
        } else {
            m3uUrl
        }
        val m3uData = text.replaceFirst("\"", "").removeSuffix("\"").replace("\\", "")
        val items: List<SeriesJsonDataModel> =
            Gson().fromJson(m3uData, itemType) //find all episodes and seasons
        return items
    }

    private suspend fun getM3uUrl(document: Document): String {
        var sourceUrl = fixUrl(document.select("iframe").attr("data-src"))

        if (sourceUrl.contains("youtube")) {
            sourceUrl = document.select("div.video-inside")
                .first { !it.select("div[data-iframe]").isEmpty() }
                .select("div[data-iframe]").attr("data-iframe")
        }
        val documentM3u = app.get(sourceUrl).document
        var m3uUrl = documentM3u.select("iframe").attr("src")
        if (m3uUrl.substringAfterLast('.') == "txt") {
            val url = withContext(Dispatchers.IO) {
                val substringAfterLast = m3uUrl.substringAfterLast("file=")
                URLDecoder.decode(substringAfterLast, "UTF-8")
            }
            return url
        }


        val getJsonData =
            "{" + documentM3u.toString().substringAfterLast("var player = new Playerjs({")
                .substringBefore(");")
        m3uUrl = Gson().fromJson(getJsonData, JsonObject::class.java).get("file").toString()

        if (m3uUrl.first() == '"') {
            m3uUrl = m3uUrl.replace("\"", "")
        }
        return m3uUrl
    }

    private suspend fun getEpisodes(document: Document): List<Episode> {
        val url = document.select("link[rel=canonical]").attr("href")
        val episodes = document.select("div.b-post__schedule_block").map { season ->

            val seasonName = season.select("div.title").text()
            return season.select("tbody > tr.current-episode").map { episode ->

                val episodeName = episode.select("td.td-1").text()
                val episodeSeason = seasonName.filter { it.isDigit() }.toInt()
                val episodePosterUrl = getEpisodePosterUrl(url, seasonName, episodeName)
                val episodeDate: Long = getEpisodeDate(episode)
                val episodeNumber = episodeName.filter { it.isDigit() }.toInt()
                val episodeSeasonTag = "$episodeName;$seasonName;$url"
                Episode(
                    data = episodeSeasonTag,
                    name = episodeName,
                    season = episodeSeason,
                    episode = episodeNumber,
                    posterUrl = episodePosterUrl,
                    date = episodeDate
                )
            }
        }

        Log.d("DEBUG getEpisodes", "Episodes: $episodes")
        if (episodes.isEmpty()) {//fix series without episodes description
            val seriesJsonDataModel = getSeriesJsonDataModel(url)
            if (seriesJsonDataModel.isNotEmpty()) {
                return seriesJsonDataModelToEpisodes(seriesJsonDataModel, url)
            }
        }

        return episodes
    }

    private fun seriesJsonDataModelToEpisodes(
        seriesJsonDataModel: List<SeriesJsonDataModel>,
        url: String
    ): List<Episode> {
        return seriesJsonDataModel.flatMap { model ->
            model.seasons.flatMap { season ->
                val seasonName = season.name
                val episodeSeasonNumber = seasonName.filter { it.isDigit() }.toInt()
                season.episodes.map { episode ->
                    val episodePosterUrl = episode.poster
                    val episodeName = episode.name
                    val episodeNumber = episode.name.filter { it.isDigit() }.toInt()
                    val episodeSeasonTag = "$episodeName;$seasonName;$url"
                    Episode(
                        data = episodeSeasonTag,
                        name = episodeName,
                        season = episodeSeasonNumber,
                        episode = episodeNumber,
                        posterUrl = episodePosterUrl,
                    )
                }
            }
        }
    }

    private suspend fun getEpisodePosterUrl(
        seriesUrl: String,
        seasonName: String,
        episodeName: String
    ): String {
        val seriesJsonDataModel =
            getSeriesJsonDataModelByEpisodeName(episodeName, seasonName, seriesUrl)
        if (seriesJsonDataModel.isEmpty()) {
            return ""
        }
        return seriesJsonDataModel.first().seasons.first().episodes.first().poster
    }

    private suspend fun getSeriesJsonDataModelByEpisodeName(
        episodeName: String,
        episodeSeasonName: String,
        seriesUrl: String
    ): List<SeriesJsonDataModel> {
        val seriesJsonDataModel = getSeriesJsonDataModel(seriesUrl)

        val season =
            seriesJsonDataModel.firstNotNullOf {
                it.seasons.firstOrNull { season ->
                    season.name.filter { seasonNameChat -> seasonNameChat.isDigit() } == episodeSeasonName.filter { episodeSeasonName -> episodeSeasonName.isDigit() }
                }
            }

        val foundEpisode =
            season.episodes.firstOrNull { episode -> episode.name.filter { c -> c.isDigit() } == episodeName.filter { c -> c.isDigit() } }

        if (foundEpisode == null) {
            return emptyList()
        }
        return listOf(
            SeriesJsonDataModel(
                "",
                listOf(Season("", listOf(foundEpisode)))
            )
        )
    }

    private fun getEpisodeDate(episode: Element): Long {
        val episodeDateText = episode.select("td.td-4").text()
        return if (episodeDateText.isNotEmpty()) SimpleDateFormat("yyyy-MM-dd").parse(
            episodeDateText
        )?.time ?: 0 else 0
    }

    private fun getDuration(document: Document): Int {
        val firstOrNull = document.select(otherDataSelector).select("li").firstOrNull {
            it.text().contains("Тривалість:")
        }
        val text = firstOrNull?.text() ?: return 0

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
            url.contains("anime") -> TvType.Movie//fix when animeseries
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