package com.lagradost

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.AnimeDetail
import com.lagradost.models.EpisodesModel
import com.lagradost.models.FindModel
import com.lagradost.models.PageProps
import com.lagradost.models.TeamsModel
import org.json.JSONArray
import org.json.JSONObject

class AniageProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://watari-anime.com"
    override var name = "Watari Anime"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = true
    override val supportedTypes =
        setOf(
            TvType.Anime,
            TvType.AnimeMovie,
            TvType.OVA,
        )

    private val apiUrl = "https://master.api.aniage.net"
    private val findUrl = "https://finder-master.api.aniage.net/?query="
    private val imageUrl = "https://image.aniage.net"
    private val videoCdn = "https://aniage-video-stream.b-cdn.net/"
    private val pageSize = 999

    private val listEpisodeModel = object : TypeToken<List<EpisodesModel>>() {}.type
    private val listTeamsModel = object : TypeToken<List<TeamsModel>>() {}.type
    private val listPageModel = object : TypeToken<List<PageProps>>() {}.type

    // Sections
    override val mainPage =
        mainPageOf(
            mainUrl to "Нове",
            mainUrl to "Повнометражне",
            mainUrl to "ONA",
            mainUrl to "OVA",
            mainUrl to "SPECIAL",
            mainUrl to "ТБ-Серіал",
            mainUrl to "ТБ-Спешл",
            mainUrl to "Короткометражне",
        )

    // Done
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val body = JSONObject()

        val cleanup =
            with(request.name) {
                when {
                    this == "Нове" -> JSONArray()
                    else ->
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("property", "type")
                                    .put("type", "=")
                                    .put("value", JSONArray().put(this))
                            )
                }
            }

        body.put("cleanup", cleanup)
        val orderBody = JSONObject()
        orderBody.put("by", "lastUpdated")
        orderBody.put("direction", "DESC")

        body.put("order", orderBody)
        body.put("page", page)
        body.put("pageSize", 30)

        val document = app.post("$apiUrl/v2/anime/find", json = body).text
        val parsedJSON = Gson().fromJson(document, FindModel::class.java)
        // Log.d("CakesTwix-Debug", parsedJSON.data[0].title)

        val homeList =
            parsedJSON.data.map {
                newAnimeSearchResponse(it.title, it.id, TvType.Anime) {
                    this.posterUrl = "$imageUrl/main/${it.posterId}?width=296"
                    addDubStatus(isDub = true, it.episodes)
                    this.otherName = it.alternativeTitle
                }
            }
        // Log.d("CakesTwix-Debug", "$cdnUrl${parsedJSON.data[1].posterId}")
        return newHomePageResponse(request.name, homeList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val animeJSON =
            Gson().fromJson<List<PageProps>>(app.get("$findUrl$query").text, listPageModel)
        val findList =
            animeJSON.map {
                newAnimeSearchResponse(it.title, it.id, TvType.Anime) {
                    this.posterUrl = "$imageUrl/main/${it.posterId}?width=296"
                    addDubStatus(isDub = true, it.episodes)
                    this.otherName = it.alternativeTitle
                }
            }
        return findList
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val animeID = url.replace("$mainUrl/watch?wid=", "").replace("$mainUrl/", "")
        val document = app.get("$mainUrl/watch/$animeID").document
        val jsonObject = JSONObject(document.selectFirst("script[type*=application/json]")!!.html())
        val buildId = jsonObject.getString("buildId")

        // https://www.aniage.net/_next/data/IfKYt_B-o41irAex5hZoV/watch.json?wid=96dcb9ce-e4bc-4248-8ed3-29c3d14aedfc
        // Log.d("CakesTwix-Debug", "$mainUrl/_next/data/$buildId/watch.json?wid=$animeID")
        val animeJSON =
            Gson()
                .fromJson(
                    app.get("$mainUrl/_next/data/$buildId/watch.json?wid=$animeID").text,
                    AnimeDetail::class.java
                )
        // Log.d("CakesTwix-Debug", animeJSON.pageProps.title)

        val showStatus =
            with(animeJSON.pageProps.titleStatus) {
                when {
                    contains("Онгоїнг") -> ShowStatus.Ongoing
                    contains("Вийшло") -> ShowStatus.Completed
                    else -> null
                }
            }

        val tvType =
            with(animeJSON.pageProps.type) {
                when {
                    contains("ТБ-Серіал") -> TvType.Anime
                    contains("ТБ-Спешл") -> TvType.Anime
                    contains("OVA") -> TvType.OVA
                    contains("SPECIAL") -> TvType.OVA
                    contains("ONA") -> TvType.OVA
                    contains("Повнометражне") -> TvType.AnimeMovie
                    contains("Короткометражне") -> TvType.AnimeMovie
                    else -> TvType.Anime
                }
            }

        val trailer =
            with(animeJSON.pageProps.trailerUrl) {
                when {
                    this.isNullOrEmpty() -> null
                    contains("https://iframe.mediadelivery.net/embed") ->
                        app.get(this)
                            .document
                            .select("source[type*=application/x-mpegURL]")
                            .attr("src")
                    else -> null
                }
            }

        // Episodes
        val episodes = mutableListOf<Episode>()

        // Log.d("CakesTwix-Debug",
        // app.get("https://master.api.aniage.net/anime/episodes?animeId=$animeID&page=1&pageSize=$pageSize&sortOrder=ASC&teamId=${animeJSON.pageProps.teams[0].teamId}&volume=1").url)
        if (animeJSON.pageProps.teams.isNotEmpty()) {
            Gson()
                .fromJson<List<EpisodesModel>>(
                    app.get(
                        "$apiUrl/anime/episodes?animeId=$animeID&page=1&pageSize=$pageSize&sortOrder=ASC&teamId=${animeJSON.pageProps.teams[0].teamId}&volume=1"
                    )
                        .text,
                    listEpisodeModel
                )
                .map {
                    val episodeName =
                        if (it.title == "." || it.title == it.episodeNum.toString())
                            "Серія ${it.episodeNum}"
                        else it.title
                    episodes.add(
                        Episode(
                            "${it.animeId}, ${it.episodeNum}",
                            episodeName,
                            episode = it.episodeNum,
                            // posterUrl = "$imageUrl/main/${it.previewPath}",
                        )
                    )
                    return@map
                }
        }
        return newAnimeLoadResponse(
            animeJSON.pageProps.title,
            "$mainUrl/watch?wid=$animeID",
            tvType,
        ) {
            this.posterUrl =
                "$imageUrl/main/${animeJSON.pageProps.posterId}?optimize=image&width=296"
            this.engName = animeJSON.pageProps.alternativeTitle
            this.tags = animeJSON.pageProps.genres.map { it }
            this.plot = animeJSON.pageProps.description
            addTrailer(trailer, addRaw = true)
            this.showStatus = showStatus
            this.duration = animeJSON.pageProps.averageDuration
            addEpisodes(DubStatus.Dubbed, episodes)
            this.year = extractIntFromString(animeJSON.pageProps.season)
        }
    }

    // It works when I click to view the series
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // animeID, Num Episode
        val dataList = data.split(", ")

        // Get Episodes List
        val document = app.get("$mainUrl/watch/${dataList[0]}").document
        val jsonObject = JSONObject(document.selectFirst("script[type*=application/json]")!!.html())
        val buildId = jsonObject.getString("buildId")

        val animeJSON =
            Gson()
                .fromJson(
                    app.get("$mainUrl/_next/data/$buildId/watch.json?wid=${dataList[0]}").text,
                    AnimeDetail::class.java
                )

        var stringTeam = "$apiUrl/anime/teams/by-ids?"
        animeJSON.pageProps.teams.map { teams -> stringTeam += "ids=${teams.teamId}&" }
        // /anime/teams/by-ids?ids=e6bff5dc-354b-4fda-98b3-c29c12931070&ids=31d156c3-1596-4dd0-8736-a01f7793c5de
        // /anime/teams/by-ids?ids=e6bff5dc-354b-4fda-98b3-c29c12931070ids=31d156c3-1596-4dd0-8736-a01f7793c5deids=c7ea3994-2841-4798-b39d-5d9389409f59

        // For names
        Gson().fromJson<List<TeamsModel>>(app.get(stringTeam).text, listTeamsModel).forEach {
                teamName ->
            Gson()
                .fromJson<List<EpisodesModel>>(
                    app.get(
                        "$apiUrl/anime/episodes?animeId=${dataList[0]}&page=1&pageSize=$pageSize&sortOrder=ASC&teamId=${teamName.id}&volume=1"
                    )
                        .text,
                    listEpisodeModel
                )
                .map {
                    if (it.episodeNum == dataList[1].toIntOrNull()) {
                        when {
                            it.playPath != null ->
                                M3u8Helper.generateM3u8(
                                    source = teamName.name,
                                    streamUrl =
                                    app.get(it.playPath)
                                        .document
                                        .select("source")
                                        .attr("src"),
                                    referer = mainUrl
                                )
                                    .forEach(callback)
                            it.s3VideoSource != null ->
                                M3u8Helper.generateM3u8(
                                    source = teamName.name,
                                    streamUrl = "$videoCdn${it.s3VideoSource.playlistPath}",
                                    referer = apiUrl
                                )
                                    .forEach(callback)
                            it.videoSource != null ->
                                M3u8Helper.generateM3u8(
                                    source = teamName.name,
                                    streamUrl =
                                    app.get(it.videoSource.playPath)
                                        .document
                                        .select("source")
                                        .attr("src"),
                                    referer = mainUrl
                                )
                                    .forEach(callback)
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
