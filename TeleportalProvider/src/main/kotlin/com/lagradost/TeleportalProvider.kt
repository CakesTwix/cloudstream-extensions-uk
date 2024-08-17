package com.lagradost

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.Media
import com.lagradost.models.MediaShows
import com.lagradost.models.Search
import com.lagradost.models.SeasonModel
import com.lagradost.models.TitleShows
import com.lagradost.models.VideoPlayer

class TeleportalProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://teleportal.ua"
    override var name = "Teleportal"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
    )

    private val apiUrl = "https://tp-back.starlight.digital"
    private val findUrl = "$apiUrl/ua/live-search?q="
    private val playerUrl = "https://vcms-api2.starlight.digital/player-api/"

    private val listSearch = object : TypeToken<List<Search>>() {}.type

    // Sections
    override val mainPage = mainPageOf(
        "$apiUrl/ua/serials" to "Серіали",
        "$apiUrl/ua/show" to "Шоу",
        "$apiUrl/ua/documentaries" to "Документальні фільми",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Movies
        if(request.data.substringAfterLast("/") == "documentaries"){
            val homeList = Gson().fromJson(app.get(request.data).text, Media::class.java).items.map{
                newAnimeSearchResponse(it.title, "$apiUrl/ua${it.videoSlug}", TvType.TvSeries) {
                    this.posterUrl = "$mainUrl${it.image}"
                }
            }
            return newHomePageResponse(request.name, homeList)
        }
        // Shows
        else {
            val homeList = Gson().fromJson(app.get(request.data).text, MediaShows::class.java).items.map{
                newAnimeSearchResponse(it.name, "$apiUrl/ua/${request.data.substringAfterLast("/")}/${it.channelSlug}/${it.projectSlug}", TvType.TvSeries) {
                    this.posterUrl = "$mainUrl${it.image}"
                }
            }
            return newHomePageResponse(request.name, homeList)
        }


    }

    override suspend fun search(query: String): List<SearchResponse> {
        return Gson().fromJson<List<Search>>(app.get("$findUrl$query").text, listSearch).map{
            newAnimeSearchResponse(it.title, "$apiUrl/ua/${it.typeSlug}/${it.channelSlug}/${it.projectSlug}", TvType.TvSeries) {
                this.posterUrl = "$mainUrl/${it.image}"
            }

        }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        //val title = app.get(url).parsedSafe<TitleShows>()!!
        val title = Gson().fromJson(app.get(url).text, TitleShows::class.java)
        val tvType = when(title.typeSlug){
            "show" -> TvType.TvSeries
            "series" -> TvType.TvSeries
            "serials" -> TvType.TvSeries
            else -> TvType.Movie
        }


        val episodes = mutableListOf<Episode>()
        if (tvType == TvType.TvSeries){
            title.seasons.map{
                val season = Gson().fromJson(app.get("$url/${it.seasonSlug}").text, SeasonModel::class.java)
                if(season.seasonGallery.items.isNullOrEmpty()) return@map
                season.seasonGallery.items.forEach { episode ->
                    episodes.add(
                        Episode(
                            "$url/${it.seasonSlug}/${episode.videoSlug}",
                            episode.title,
                            extractIntFromString(season.seasonTitle),
                            extractIntFromString(episode.seriesTitle),
                            "$mainUrl${episode.image}",
                            description = episode.tizer,
                        )
                    )
                }
            }
            return newAnimeLoadResponse(
                title.title,
                "$mainUrl${title.projectSlug}",
                tvType,
            ) {
                this.posterUrl = "$mainUrl${title.image}"
                this.plot = title.description
                addEpisodes(DubStatus.Dubbed, episodes)
            }
        }

        return newMovieLoadResponse(title.title, url, TvType.Movie, "$apiUrl/ua/${title.typeSlug}/${title.channelSlug}/${title.videoSlug}") {
            this.posterUrl = "$mainUrl${title.image}"
            this.plot = title.description
        }
    }

    // It works when I click to view the series
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Log.d("CakesTwix-Debug", data)
        val videoHash = Gson().fromJson(app.get(data).text, TitleShows::class.java).hash
        // Log.d("CakesTwix-Debug", "$playerUrl$videoHash?referer=https://teleportal.ua/")
        val video = Gson().fromJson(app.get("$playerUrl$videoHash?referer=https://teleportal.ua/").text, VideoPlayer::class.java).video[0]
        // Log.d("CakesTwix-Debug", video.toString())

        if(video.mediaHlsNoAdv.isNullOrEmpty()) return false

        // Log.d("CakesTwix-Debug", video.mediaHlsNoAdv)
        M3u8Helper.generateM3u8(
            source = video.projectName,
            streamUrl = video.mediaHlsNoAdv,
            referer = mainUrl
        ).last().let(callback)
        return true
    }

    private fun extractIntFromString(string: String): Int? {
        val value = Regex("(\\d+)").findAll(string).lastOrNull() ?: return null
        if(value.value[0].toString() == "0"){
            return value.value.drop(1).toIntOrNull()
        }

        return value.value.toIntOrNull()

    }
}
