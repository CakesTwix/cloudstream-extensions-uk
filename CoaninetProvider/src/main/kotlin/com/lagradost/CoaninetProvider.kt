package com.lagradost

import android.util.Log.d
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.model.AnimeResponse
import com.lagradost.model.Response
import com.lagradost.model.SeasonResponse
import com.lagradost.model.SeriesData
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CoaninetProvider : MainAPI() {

    private val episodeRequestUrl = "https://coani.net/api/public/film/season?slug="
    private val movieSelector = "div.data-listing"
    private val titleSelector = "div.film-card-v2__info > a"
    private val posterUrlSelector = "div.film-card-v2 > a > img"
    private val mapper = JsonMapper.builder()
        .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
        .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
        .addModule(KotlinModule.Builder().build()).build()

    // Basic Info
    override var mainUrl = "https://coani.net"
    override var name = "Coaninet"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
    )

    // Sections
    override val mainPage = mainPageOf(
//        "$mainUrl/" to "Новинки",
        "$mainUrl/?page=" to "Аніме",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
//        d("getMainPage", "page:$page request:${request.data}")
        val url = request.data + page
//        d("url", url)
        val document = app.get(url).document
        val first = document.select(movieSelector)
            .firstOrNull()

//        d("getMainPage", "first:$first.")
        return if (first == null) {
            newHomePageResponse(request.name, emptyList())
        } else {
            val mainPage = first.children().map {
                it.toSearchResponse()
            }.filter { !it.posterUrl.isNullOrEmpty() }
//            d("getMainPage", "mainPage:$mainPage")
            newHomePageResponse(request.name, mainPage)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
//        d("search", "query:$query")
        val url = "$mainUrl/api/public/film/catalog?search=$query"
//        d("DEBUG search", "url:$url")
        val document = app.get(url).document
//        d("DEBUG search", "document:$document")
        val readValue = mapper.readValue<Response>(document.select("body").html())
//        d("DEBUG search", "readValue:$readValue")

        return readValue.data.map {
            val data = it.data
            val title = data.name
            val itemUrl = "$mainUrl/catalog/${data.filmSeoSlug}/${data.seoSlug}"
            val posterUrl = data.preview.previewMain
            newMovieSearchResponse(title, itemUrl, TvType.Movie) {
//                d("newMovieSearchResponse", "posterUrl:$posterUrl")
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        d("DEBUG load", "url:$url")
        val document = app.get(url).document
//        d("DEBUG load", "document:$document")


        /*
        *
        *   get season:
        * 1)https://coani.net/api/public/film/season?slug=oshi-no-ko-season-2
        * get id from response
        * {
            "type": "season",
            "data": {
                "context": {
                    "is_viewed": false,
                    "series_viewed": 0,
                    "series_viewed_percent": 0,
                    "last_viewed_seria": 0
                },
                "id": 156,
                *
                *
                *
                * 2) get id for each season
                * https://coani.net/api/public/film/season?slug=oshi-no-ko-season-2
                * {
            "type": "season",
            "data": {
                "context": {
                    "is_viewed": false,
                    "series_viewed": 0,
                    "series_viewed_percent": 0,
                    "last_viewed_seria": 0
                },
                "id": 156,

                *https://coani.net/api/public/film/season?slug=oshi-no-ko-season-3
                * {
            "type": "season",
            "data": {
                "context": {
                    "is_viewed": false,
                    "series_viewed": 0,
                    "series_viewed_percent": 0,
                    "last_viewed_seria": 0
                },
                "id": 157,
                *
                * 3)get m3u video url for each episod of each season m3u
                * episod preview
                *

        *
        *
        * */


        // Collect all seasons at the start.
        // `null` means there is no selector, so reuse the already-loaded document.
        val seasons: List<Pair<Int, String>> = document
            .select(".seasons-tabs a.seasons-tab[href]")
            .mapNotNull { element ->
                val seasonNumber = """\d+""".toRegex()
                    .find(element.text())
                    ?.value
                    ?.toIntOrNull()

                seasonNumber?.let {
                    it to fixUrl(element.attr("href"))
                }
            }
            .distinctBy { it.second }
            .ifEmpty {
                listOf(1 to "")
            }

        d("DEBUG load", "seasons:$seasons")

        val title = getPageTitle(document)
        val engTitle = getPageEngTitle(document)
        val posterUrl = getPagePosterUrl(document)
        val year = getYear(document)
        val description = getDescription(document)
        val tags = getGenres(document)
        val actors = getActors()
        val ratingValue = getRating(document)
        val episodes = mutableListOf<Episode>()

        for ((seasonNumber, seasonUrl) in seasons) {


            episodes += getSeasonEpisodes(
                seasonUrl = seasonUrl,
                parentUrl = url,
                seasonNumber = seasonNumber,
            )
        }

//        d("load", "seasons: ${seasons.map { it.first }}")
//        d("load", "episodes: $episodes")


        return newAnimeLoadResponse(title, url, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.engName = engTitle
            this.year = year
            this.plot = description
            this.tags = tags
            this.contentRating = ratingValue
            addActors(actors)
            addEpisodes(DubStatus.Dubbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        d("DEBUG loadLinks", "Data: $data")

        // 1. Deserialize as a List of SeriesData objects (all available dubs for this episode)
        val seriesList = mapper.readValue<List<SeriesData>>(data)

        if (seriesList.isEmpty()) {
            return false
        }

        // 2. Loop through every available dub/voice option for this episode
        seriesList.forEach { seriesData ->
            val savedM3uUrl = seriesData.video.orEmpty()
            val dubName = seriesData.voiceType.takeIf { !it.isNullOrEmpty() } ?: this.name

            if (savedM3uUrl.isNotBlank()) {
                // Generate links for each dub option
                M3u8Helper.generateM3u8(
                    source = dubName, // Displays as player source choice (e.g. "Колонія", "Glass Building", etc.)
                    streamUrl = savedM3uUrl,
                    referer = mainUrl
                ).forEach(callback)
            }
        }

        return true
    }

    private fun getActors() = emptyList<String>()

    private fun getGenres(document: Document) =
        document.select("h5.film-info__row_value h6 film-info__row_value_links > a")
            .map { it.text() }


    private fun getDescription(document: Document) =
        document.select("div.film-info__description").text()

    private fun getRating(document: Document) =
        document.selectFirst("span.season-rating__total-value")?.text()

    private suspend fun getSeasonEpisodes(
        seasonUrl: String,
        parentUrl: String,
        seasonNumber: Int,
    ): List<Episode> = coroutineScope {
        d("DEBUG getSeasonEpisodes", "seasonUrl:$seasonUrl parentUrl:$parentUrl")

        val seasonUrlName = seasonUrl.substringAfterLast('/')
        val seasonUrlNameRawResponseText = app.get("$episodeRequestUrl$seasonUrlName").text
        val seasonId = mapper.readValue<SeasonResponse>(seasonUrlNameRawResponseText).data?.id

        val url = "https://coani.net/api/public/film/season/$seasonId/series"
        val rawResponseText = app.get(url).text

        val readValue = mapper.readValue<AnimeResponse>(rawResponseText)

        // 1. Group all items by episode number
        val episodesByNumber = readValue.data.groupBy { it.data?.number }

        // 2. Create ONE Episode per episode number
        episodesByNumber.mapNotNull { (epNumber, items) ->
            if (epNumber == null) return@mapNotNull null

            // Extract list of all SeriesData objects for this episode
            val seriesDataList: List<SeriesData> = items.mapNotNull { it.data }
            val firstItem = seriesDataList.firstOrNull() ?: return@mapNotNull null

            val posterUrlValue = firstItem.images?.poster ?: ""

            newEpisode(parentUrl) {
                posterUrl = posterUrlValue
                name = "Серія $epNumber"
                season = seasonNumber
                episode = epNumber
                // Store ALL dubs/streams for this episode as a JSON array string
                data = mapper.writeValueAsString(seriesDataList)
            }
        }
    }

    private fun Element.toSearchResponse(): MovieSearchResponse {
        d("toSearchResponse", "$this")
        val title = this.select(titleSelector).text()
        val url = this.select(titleSelector).attr("href")
        val attr = this.select(posterUrlSelector).attr("src")
        val posterUrl = attr.ifEmpty {
            ""
        }

        return newMovieSearchResponse(title, url, TvType.Movie) {
            d("newMovieSearchResponse", "posterUrl:$posterUrl")
            this.posterUrl = posterUrl
        }
    }

    private fun getYear(document: Document): Int {
        return document.selectFirst("div.seasons-tabs")
            ?.selectFirst("a.seasons-tab--active")
            ?.selectFirst("div.seasons-tab__meta")
            ?.select("span")
            ?.first()
            ?.text()?.toInt() ?: 0
    }

    private fun getPagePosterUrl(document: Document) =
        document.selectFirst("div.film-info__preview > img")?.attr("src") ?: ""

    private fun getPageEngTitle(document: Document) =
        document.selectFirst("span.film-info__row_name:contains(Оригінальна назва:)")?.text()
            ?.trim()

    private fun getPageTitle(document: Document) = document.select("title").text()

}
