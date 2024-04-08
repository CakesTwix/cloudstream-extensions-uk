package com.lagradost

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.AnimeInfoModel
import com.lagradost.models.AnimeModel
import com.lagradost.models.NewAnimeModel
import com.lagradost.models.SearchModel
import com.lagradost.models.PlayerJson

class AnimeONProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://animeon.club"
    override var name = "AnimeON"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = true
    override val supportedTypes =
        setOf(
            TvType.Anime,
            TvType.AnimeMovie,
            TvType.OVA,
        )

    private val apiUrl = "$mainUrl/api/anime/"
    private val posterApi = "$mainUrl/api/uploads/images/%s"
    private val searchApi = "$apiUrl/search/%s?full=true"

    // Sections
    override val mainPage =
        mainPageOf(
            "$apiUrl/popular" to "Популярне",
            "$apiUrl/seasons" to "Аніме поточного сезону ",
            "$apiUrl?pageSize=24&pageIndex=%s&sortType=DESC&sort=created" to "Нове",
        )

    private val listAnimeModel = object : TypeToken<List<AnimeModel>>() {}.type

    // Done
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if(!request.data.contains("pageIndex") && page !=1) return HomePageResponse(emptyList())
        val document = app.get(request.data.format(page)).text

        // Нове
        if(request.data.contains("pageIndex")) {
            val parsedJSON = Gson().fromJson(document, NewAnimeModel::class.java)
            val homeList =
                    parsedJSON.results.map {
                        newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                            this.posterUrl = posterApi.format(it.poster)
                        }
                    }
            // Log.d("CakesTwix-Debug", "$cdnUrl${parsedJSON.data[1].posterId}")
            return newHomePageResponse(request.name, homeList)
        } else {
            val parsedJSON = Gson().fromJson<List<AnimeModel>>(document, listAnimeModel)
            val homeList =
                    parsedJSON.map {
                        newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                            this.posterUrl = posterApi.format(it.poster)
                        }
                    }
            // Log.d("CakesTwix-Debug", "$cdnUrl${parsedJSON.data[1].posterId}")
            return newHomePageResponse(request.name, homeList)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val animeJSON =
            Gson().fromJson(app.get(searchApi.format(query)).text, SearchModel::class.java)
        val findList =
            animeJSON.result.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.poster)
                    addDubStatus(isDub = true, it.episodes)
                }
            }
        return findList
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val animeJSON =
                Gson()
                        .fromJson(
                                app.get(url.replace("/anime/", "/api/anime/")).text,
                                AnimeInfoModel::class.java
                        )

        val showStatus =
                with(animeJSON.status.name) {
                    when {
                        contains("Онґоїнґ") -> ShowStatus.Ongoing
                        contains("Завершений") -> ShowStatus.Completed
                        else -> null
                    }
                }

        val tvType =
                with(animeJSON.type.name) {
                    when {
                        contains("ТБ-Серіал") -> TvType.Anime
                        contains("OVA") -> TvType.OVA
                        contains("Спеціальний випуск") -> TvType.OVA
                        contains("ONA") -> TvType.OVA
                        contains("Фільм") -> TvType.AnimeMovie
                        else -> TvType.Anime
                    }
                }


        val episodes = mutableListOf<Episode>()

        val playerRawJson = app.get(animeJSON.player.url).document.select("script").html()
                .substringAfterLast("file:\'")
                .substringBefore("\',")

        tryParseJson<List<PlayerJson>>(playerRawJson)?.map { dubs -> // Dubs
            for (season in dubs.folder) {                              // Seasons
                for (episode in season.folder) {                       // Episodes
                    episodes.add(
                            Episode(
                                    "${season.title}, ${episode.title}, ${animeJSON.player.url}",
                                    episode.title,
                                    season.title.replace(" Сезон ", "").toIntOrNull(),
                                    episode.title.replace("Серія ", "").toIntOrNull(),
                                    episode.poster
                            )
                    )
                }
            }
        }

        return if (tvType == TvType.Anime || tvType == TvType.OVA) {
            newAnimeLoadResponse(
                    animeJSON.titleUa,
                    "$mainUrl/anime/${animeJSON.id}",
                    tvType,
            ) {
                this.posterUrl = posterApi.format(animeJSON.poster)
                this.engName = animeJSON.titleEn
                this.tags = animeJSON.genres.map { it.name }
                this.plot = animeJSON.description
                addTrailer(animeJSON.trailer)
                this.showStatus = showStatus
                this.duration = extractIntFromString(animeJSON.episodeTime)
                this.year = animeJSON.releaseDate
                this.backgroundPosterUrl = posterApi.format(animeJSON.backgroundImage)
                this.rating = animeJSON.rating.toString().toRatingInt()
                addEpisodes(DubStatus.Dubbed, episodes)
                addMalId(animeJSON.malId)
            }
        } else {
            newMovieLoadResponse(animeJSON.titleUa, "$mainUrl/anime/${animeJSON.id}", tvType, "${animeJSON.titleUa}, ${animeJSON.player.url}") {
                this.posterUrl = posterApi.format(animeJSON.poster)
                this.tags = animeJSON.genres.map { it.name }
                this.plot = animeJSON.description
                addTrailer(animeJSON.trailer)
                this.duration = extractIntFromString(animeJSON.episodeTime)
                this.year = animeJSON.releaseDate
                this.backgroundPosterUrl = posterApi.format(animeJSON.backgroundImage)
                this.rating = animeJSON.rating.toString().toRatingInt()
                addMalId(animeJSON.malId)
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
            val m3u8Url = app.get(dataList[1]).document.select("script").html()
                    .substringAfterLast("file:\"")
                    .substringBefore("\",")
            M3u8Helper.generateM3u8(
                    source = dataList[0],
                    streamUrl = m3u8Url,
                    referer = "https://tortuga.wtf/"
            ).forEach(callback)

            return true
        }

        val playerRawJson = app.get(dataList[2]).document.select("script").html()
                .substringAfterLast("file:\'")
                .substringBefore("\',")

        tryParseJson<List<PlayerJson>>(playerRawJson)?.map { dubs ->   // Dubs
            for(season in dubs.folder){                                // Seasons
                if(season.title == dataList[0]){
                    for(episode in season.folder){                     // Episodes
                        if(episode.title == dataList[1]){
                            // Add as source
                            M3u8Helper.generateM3u8(
                                    source = dubs.title,
                                    streamUrl = episode.file,
                                    referer = "https://tortuga.wtf/"
                            ).forEach(callback)
                        }
                    }
                }
            }
        }
        return true
    }

    private fun extractIntFromString(string: String): Int? {
        val value = Regex("(\\d+)").findAll(string).lastOrNull() ?: return null
        if (value.value[0].toString() == "0") {
            return value.value.drop(1).toIntOrNull()
        }

        return value.value.toIntOrNull()
    }
}
