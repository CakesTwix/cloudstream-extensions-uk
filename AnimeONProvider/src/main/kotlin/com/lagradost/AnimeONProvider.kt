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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.AnimeInfoModel
import com.lagradost.models.AnimeModel
import com.lagradost.models.FundubEpisode
import com.lagradost.models.FundubModel
import com.lagradost.models.FundubVideoUrl
import com.lagradost.models.NewAnimeModel
import com.lagradost.models.PlayerEpisodes
import com.lagradost.models.SearchModel

class AnimeONProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://animeon.club"
    override var name = "AnimeON"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes =
        setOf(
            TvType.Anime,
            TvType.AnimeMovie,
            TvType.OVA,
        )

    private val apiUrl = "$mainUrl/api/anime"
    private val posterApi = "$mainUrl/api/uploads/images/%s"
    private val searchApi = "$apiUrl/search?=text="

    // Sections
    override val mainPage =
        mainPageOf(
            "$apiUrl/popular" to "Популярне",
            "$apiUrl/seasons" to "Аніме поточного сезону ",
            "$apiUrl?pageSize=24&pageIndex=%d" to "Нове",
        )

    private val listAnimeModel = object : TypeToken<List<AnimeModel>>() {}.type
    private val listFundub = object : TypeToken<List<FundubModel>>() {}.type

    // Done
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if(!request.data.contains("pageIndex") && page !=1) return HomePageResponse(emptyList())
        val document = app.get(request.data.format(page),
            headers = mapOf(
                "Referer" to mainUrl,
            )).text

        // Нове
        if(request.data.contains("pageIndex")) {
            val parsedJSON = Gson().fromJson(document, NewAnimeModel::class.java)
            val homeList =
                    parsedJSON.results.map {
                        newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                            this.posterUrl = posterApi.format(it.image.preview)
                        }
                    }
            // Log.d("CakesTwix-Debug", "$cdnUrl${parsedJSON.data[1].posterId}")
            return newHomePageResponse(request.name, homeList)
        } else {
            val parsedJSON = Gson().fromJson<List<AnimeModel>>(document, listAnimeModel)
            val homeList =
                    parsedJSON.map {
                        newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                            this.posterUrl = posterApi.format(it.image.preview)
                        }
                    }
            // Log.d("CakesTwix-Debug", "$cdnUrl${parsedJSON.data[1].posterId}")
            return newHomePageResponse(request.name, homeList)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val animeJSON =
            Gson().fromJson(app.get(searchApi + query,
                headers = mapOf(
                    "Referer" to mainUrl,
            )).text, SearchModel::class.java)

        val findList =
            animeJSON.result.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
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
                                app.get(url.replace("/anime/", "/api/anime/"),
                                    headers = mapOf(
                                        "Referer" to "$mainUrl/",
                                    )).text,
                                AnimeInfoModel::class.java
                        )
        val showStatus =
                with(animeJSON.status) {
                    when {
                        contains("ongoing") -> ShowStatus.Ongoing
                        contains("released") -> ShowStatus.Completed
                        else -> ShowStatus.Completed
                    }
                }

        val tvType =
                with(animeJSON.type!!) {
                    when {
                        contains("tv") -> TvType.Anime
                        contains("OVA") -> TvType.OVA
                        contains("Спеціальний випуск") -> TvType.OVA
                        contains("ONA") -> TvType.OVA
                        contains("movie") -> TvType.AnimeMovie
                        else -> TvType.Anime
                    }
                }

        val episodes = mutableListOf<Episode>()

        // Get all fundub for title and parse only first fundub/player
        // https://animeon.club/api/player/fundubs/6966
        val fundubs = Gson().fromJson<List<FundubModel>>(app.get("$mainUrl/api/player/fundubs/${animeJSON.id}",
            headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; rv:126.0) Gecko/20100101 Firefox/126.0",
            )).text, listFundub)

        Gson().fromJson(app.get("$mainUrl/api/player/episodes/${animeJSON.id}?playerId=${fundubs?.get(0)?.player?.get(0)?.id}&fundubId=${fundubs?.get(0)?.fundub?.id}",
            headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; rv:126.0) Gecko/20100101 Firefox/126.0",
            )).text, PlayerEpisodes::class.java).episodes.map { epd -> // Episode
            episodes.add(
                    Episode(
                            "${animeJSON.id}, ${epd.episode}",
                            "Епізод ${epd.episode}",
                            episode = epd.episode,
                            posterUrl = epd.poster
                    )
            )
        }
        return if (tvType == TvType.Anime || tvType == TvType.OVA) {
            newAnimeLoadResponse(
                    animeJSON.titleUa,
                    "$mainUrl/anime/${animeJSON.id}",
                    tvType,
            ) {
                this.posterUrl = posterApi.format(animeJSON.image.preview)
                this.engName = animeJSON.titleEn
                this.tags = animeJSON.genres.map { it.nameUa }
                this.plot = animeJSON.description
                addTrailer(animeJSON.trailer)
                this.showStatus = showStatus
                this.duration = extractIntFromString(animeJSON.episodeTime)
                this.year = animeJSON.releaseDate.toIntOrNull()
                this.rating = animeJSON.rating.toRatingInt()
                addEpisodes(DubStatus.Dubbed, episodes)
                addMalId(animeJSON.malId.toIntOrNull())
            }
        } else {
            var backgroundImage = animeJSON.backgroundImage
            if(backgroundImage.isNullOrBlank()){
                backgroundImage = posterApi.format(animeJSON.image.preview)
            } else {
                backgroundImage = posterApi.format(animeJSON.screenshots.first().original)
            }
            newMovieLoadResponse(animeJSON.titleUa, "$mainUrl/anime/${animeJSON.id}", tvType, "${animeJSON.id}") {
                this.posterUrl = posterApi.format(animeJSON.image.preview)
                this.tags = animeJSON.genres.map { it.nameUa }
                this.plot = animeJSON.description
                addTrailer(animeJSON.trailer)
                this.duration = extractIntFromString(animeJSON.episodeTime)
                this.year = animeJSON.releaseDate.toIntOrNull()
                this.backgroundPosterUrl = backgroundImage
                this.rating = animeJSON.rating.toRatingInt()
                addMalId(animeJSON.malId.toIntOrNull())
            }
        }
    }


    // It works when I click to view the series
    override suspend fun loadLinks(
            data: String, // (Serisl) [id title, episode] | (Film) ?
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split(", ")
        val fundubs = Gson().fromJson<List<FundubModel>>(app.get("$mainUrl/api/player/fundubs/${dataList[0]}",
            headers = mapOf(
                "Referer" to mainUrl,
            )).text, listFundub)

        if(dataList.size == 2){
            fundubs.map { dub ->
                Gson().fromJson(app.get("$mainUrl/api/player/episodes/${dataList[0]}?playerId=${dub.player[0].id}&fundubId=${dub.fundub.id}",
                    headers = mapOf(
                        "Referer" to mainUrl,
                    )).text, PlayerEpisodes::class.java).episodes.filter{ it.episode == dataList[1].toIntOrNull() }.map { epd -> // Episode

                        M3u8Helper.generateM3u8(
                            source = "${dub.fundub.name} (${dub.player[0].name})",
                            streamUrl = getM3U(app.get("$mainUrl/api/player/episode/${epd.id}",
                                headers = mapOf(
                                    "Referer" to mainUrl,
                                )).parsedSafe<FundubVideoUrl>()!!.videoUrl),
                            referer = "https://moonanime.art/iframe/rnxylfdgutgcfekzljkq/?player=animeon.club",
                            headers = mapOf("User-Agent" to  "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0",
                                "Accept" to "*/*",
                                "accept-language" to "uk,ru;q=0.9,en-US;q=0.8,en;q=0.7",
                                "origin" to "https://moonanime.art")
                    ).last().let(callback)
                }
            }
            return true
        }

        fundubs.map { dub ->
            M3u8Helper.generateM3u8(
                    source = "${dub.fundub.name} (${dub.player[0].name})",
                    streamUrl = getM3U(app.get("${apiUrl}/player/${dataList[0]}/${dub.player[0].id}/${dub.fundub.id}",
                        headers = mapOf(
                            "Referer" to mainUrl,
                        )).parsedSafe<FundubVideoUrl>()!!.videoUrl),
                    referer = ""
            ).last().let(callback)
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

    private suspend fun getM3U(url: String): String{
        with(url){
            when {
                contains("https://moonanime.art") -> {
                    val document = app.get(this,
                            headers = mapOf(
                                    "Host" to "moonanime.art",
                                    "Accept" to "*/*",
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; rv:126.0) Gecko/20100101 Firefox/126.0",
                                    "accept-language" to "en-US,en;q=0.5"
                            )).document

                    return document.select("script").html()
                            .substringAfterLast("file: \"")
                            .substringBefore("\",")
                }

                contains("https://ashdi.vip/vod") -> {
                    return app.get(this).document.select("script").html()
                            .substringAfterLast("file:\"")
                            .substringBefore("\",")
                }

                else -> return ""
            }
        }
    }
}
