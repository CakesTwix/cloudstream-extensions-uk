package com.lagradost

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.readValue
//import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
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
    private val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    private val mapper = JsonMapper.builder()
        .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
        .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
        .build()

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
            .firstOrNull()


        return if (first == null) {
            newHomePageResponse(request.name, emptyList())
        } else {
            val mainPage = first.children().map {
                it.toSearchResponse()
            }.filter { !it.posterUrl.isNullOrEmpty() }
            newHomePageResponse(request.name, mainPage)
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
//        Log.d("DEBUG load", "Url: $url")
        val document = app.get(url).document

        return when (val tvType = getTvType(url)) {
            TvType.Movie, TvType.Cartoon -> {//videos with 1 episode
                getNewMovieLoadResponse(document, tvType, url)
            }

            TvType.Anime -> {
                val episodes = getEpisodes(document)

                if (episodes.isNotEmpty()) {//multiple episodes
                    getNewTvSeriesLoadResponse(document, tvType, url, episodes)
                } else {//one episode
                    getNewMovieLoadResponse(document, tvType, url)
                }
            }

            else -> { //TvSeries
                val episodes = getEpisodes(document)

                getNewTvSeriesLoadResponse(document, tvType, url, episodes)
            }
        }

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
//        Log.d("DEBUG loadLinks", "Data: $data")
        val tvType = if (data.startsWith("http")) {
            TvType.Movie
        } else {
            TvType.TvSeries
        }

        return when (tvType) {
            TvType.Movie -> {//movie cartoon
                val document = app.get(data).document
                val m3uUrl = getM3uUrl(document)
                val dubName = getMovieDubName(document)


                if (m3uUrl.endsWith(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        source = dubName,
                        streamUrl = m3uUrl,
                        referer = "https://uk.uatut.fun/"
                    ).last().let(callback)
                } else {
                    val m3u8 = app.get(m3uUrl)
                    val jsonArray = mapper.readTree(m3u8.text)
                    val m3uFileUrl = jsonArray.firstOrNull { nodes -> !nodes.get("file").isNull }

                    val m3u8DirectFileUrl = m3uFileUrl?.get("file")?.textValue() ?: ""

                    M3u8Helper.generateM3u8(
                        source = dubName,
                        streamUrl = m3u8DirectFileUrl,
                        referer = "https://uk.uatut.fun/"
                    ).forEach(callback)
                }

                true
            }

            else -> {//series
                val (episodeName, episodeSeasonName, seriesUrl) = data.split(";")
//                Log.d(
//                    "DEBUG loadLinks", "EpisodeName: $episodeName, SeasonName:" +
//                            " $episodeSeasonName, SeriesUrl: $seriesUrl"
//                )

                val jsonDataModel =
                    getSeriesJsonDataModelByEpisodeName(episodeName, episodeSeasonName, seriesUrl)
                if (jsonDataModel.isEmpty()) {
                    false
                } else {
                    val sourceDubName = jsonDataModel.first().seriesDubName
                    val m3u8DirectFileUrl =
                        jsonDataModel.first().seasons.first().episodes.first().file
                    M3u8Helper.generateM3u8(
                        source = sourceDubName,
                        streamUrl = m3u8DirectFileUrl,
                        referer = "https://uk.uatut.fun/"
                    ).forEach(callback)
                    true
                }
            }
        }
    }

    private fun getMovieDubName(document: Document): String {
        val keyword = "Озвучення:"
        val delimiter = "|"
        val text = document.select("ul.pmovie__list").select("li")
            .firstOrNull { it.text().contains(keyword) }?.text() ?: ""

        if (text.contains(delimiter)) {
            return text.substringAfterLast(delimiter).trim()
        }
        return text.replace(keyword, "").trim()
    }

    private fun Element.toSearchResponse(): MovieSearchResponse {
        val title = this.select(titleSelector).text()
        val url = this.attr(videoUrlSelector)
        val posterUrl = fixUrl(
            this.select(posterUrlSelector)
                .attr("data-src")
        )
//        Log.d("DEBUG MovieSearchResponse", "seriesUrl: $posterUrl")

        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    private suspend fun getNewTvSeriesLoadResponse(
        document: Document,
        tvType: TvType,
        url: String,
        episodes: List<Episode>
    ): TvSeriesLoadResponse {
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

        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = tags
            this.year = year
            this.rating = rating
            this.name = "$title ($engTitle)"
            addActors(actors)
            addTrailer(trailerUrl)
            this.duration = duration
        }

    }

    private suspend fun getNewMovieLoadResponse(
        document: Document,
        tvType: TvType,
        url: String
    ): LoadResponse {

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

        return newMovieLoadResponse(title, url, tvType, url) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = tags
            this.year = year
            this.rating = rating
            this.name = "$title ($engTitle)"
            addActors(actors)
            addTrailer(trailerUrl)
            this.duration = duration
        }
    }


    private suspend fun getSeriesJsonDataModel(
        seriesUrl: String,
    ): List<SeriesJsonDataModel> {
        val document = app.get(seriesUrl).document
//        Log.d("DEBUG getSeriesJsonDataModel", "seriesUrl: $seriesUrl document: ${document.childrenSize()}")
        val m3uUrl = getM3uUrl(document)

        val text = if (m3uUrl.startsWith("http") && m3uUrl.endsWith(".txt")) {
            app.get(m3uUrl).text
        } else if (m3uUrl.isNotEmpty()) {
            return try {
                val stringToJson = stringToJson(m3uUrl)
                getObjectFromJson(stringToJson)
            } catch (e: Exception) {
                System.err.println(e)
                emptyList()
            }
        } else {
            return emptyList()
        }

        val m3uData = stringToJson(text)


//        Log.d("DEBUG getSeriesJsonDataModel", "Text: $text")
        //find all episodes and seasons
        return try {
            getObjectFromJson(m3uData)
        } catch (e: Exception) {
            System.err.println(e)
            emptyList()
        }
    }

    private fun stringToJson(text: String): String {
        var m3uData = text.replace("\\", "")

        if (m3uData.startsWith("\"")) {
            m3uData = m3uData.replaceFirst("\"", "")
        }
        if (m3uData.endsWith("\"")) {
            m3uData = m3uData.replaceAfterLast("\"", "")
        }
        return m3uData
    }

    private fun getObjectFromJson(m3uData: String): List<SeriesJsonDataModel> {
        val result = mutableListOf<SeriesJsonDataModel>()
        val items: List<SeriesJsonDataModel> = mapper.readValue<List<SeriesJsonDataModel>>(m3uData)
        val seriesDubCheck = items.first()?.seriesDubName ?: ""

        if (seriesDubCheck.isEmpty()) {
            val episodesList: List<com.lagradost.model.Episode> = mapper.readValue(m3uData)
            episodesList.forEach { episode ->
                val episodeName = episode?.name ?: ""
                if (episodeName.isEmpty()) {
                    episode.name = "1"
                }
            }
            result.add(SeriesJsonDataModel("1", listOf(Season("1", episodesList))))
        } else {
            result.addAll(items)
        }

        return result
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
        if (m3uUrl.endsWith(".txt")) {
            val url = with(m3uUrl) {
                val substringAfterLast = m3uUrl.substringAfterLast("file=")
                URLDecoder.decode(substringAfterLast, "UTF-8")
            }
            return url
        }


        val getJsonData =
            "{" + documentM3u.toString().substringAfterLast("var player = new Playerjs({")
                .substringBefore(");")

        m3uUrl = mapper.readTree(getJsonData).get("file").asText()

        if (m3uUrl.first() == '"') {
            m3uUrl = m3uUrl.replace("\"", "")
        }
        return m3uUrl
    }

    private suspend fun getEpisodes(document: Document): List<Episode> {
        val url = document.select("link[rel=canonical]").attr("href")
        val episodes = document.select("div.b-post__schedule_block").flatMap { season ->

            val seasonName = season.select("div.title").text()
            season.select("tbody > tr.current-episode").map { episode ->

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

//        Log.d("DEBUG getEpisodes", "Episodes: $episodes")
        if (episodes.isEmpty()) {//fix series without episodes description
            val seriesJsonDataModel = getSeriesJsonDataModel(url)
            val collectionOrObjectNotNull =
                seriesJsonDataModel.isNotEmpty() && seriesJsonDataModel.firstOrNull() != null

            val objectFieldsNotNull = if (collectionOrObjectNotNull) {
                seriesJsonDataModel.first()?.seasons
            } else {
                null
            }


            if (collectionOrObjectNotNull && objectFieldsNotNull != null) {
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
//        Log.d("DEBUG getEpisodePosterUrl", "seriesUrl: $seriesUrl")
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
//        Log.d(
//            "DEBUG getSeriesJsonDataModelByEpisodeName",
//            "EpisodeName: $episodeName, SeasonName: $episodeSeasonName, SeriesUrl: $seriesUrl"
//        )
        val seriesJsonDataModel = getSeriesJsonDataModel(seriesUrl)
//        Log.d(
//            "DEBUG getSeriesJsonDataModelByEpisodeName",
//            "SeriesJsonDataModel: $seriesJsonDataModel"
//        )
        val seasonsList = seriesJsonDataModel.firstOrNull()?.seasons ?: return emptyList()

//        Log.d("DEBUG getSeriesJsonDataModelByEpisodeName", "SeasonsList: $seasonsList")
//
        val season = seasonsList.firstOrNull { season ->
            season.name.filter { seasonName -> seasonName.isDigit() } == episodeSeasonName
                .filter { episodeSeasonName -> episodeSeasonName.isDigit() }
        }
//        Log.d("DEBUG getSeriesJsonDataModelByEpisodeName", "Season: $season")
//
        val foundEpisode =
            season?.episodes?.firstOrNull { episode ->
                episode.name
                    .filter { c -> c.isDigit() } == episodeName.filter { c -> c.isDigit() }
            }

        if (foundEpisode == null) {
            return emptyList()
        }
        val seriesDubName = seriesJsonDataModel.first().seriesDubName
        return listOf(
            SeriesJsonDataModel(
                seriesDubName,
                listOf(Season("", listOf(foundEpisode)))
            )
        )
    }

    private fun getEpisodeDate(episode: Element): Long {
//        Log.d("DEBUG getEpisodeDate", "Episode: $episode")
        val episodeDateText = episode.select("td.td-4").text()
//        Log.d("DEBUG getEpisodeDate", "EpisodeDateText: $episodeDateText")
        return if (episodeDateText.isNotEmpty()) {
            simpleDateFormat.parse(
                episodeDateText
            )?.time ?: 0
        } else 0
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
            .firstOrNull { it.text().contains("Актори:") }?.select("a")
            ?.map { it.text() }?.toList() ?: emptyList()
    }

    private fun getTags(document: Document): List<String> {
        return document.select(otherDataSelector).select("li")
            .firstOrNull { element -> element.text().contains("Жанр:") }?.select("a")
            ?.map { it.text().replaceFirstChar { firstChat -> firstChat.uppercase() } }?.toList()
            ?: emptyList()
    }

    private fun getYear(document: Document): Int {
        val yearIndexTag = "Рік виходу:"
        return document.select(otherDataSelector).select("li")
            .firstOrNull { it.select("span").text() == yearIndexTag }?.text()
            ?.replace(yearIndexTag, "")?.trim()?.toInt() ?: 0
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