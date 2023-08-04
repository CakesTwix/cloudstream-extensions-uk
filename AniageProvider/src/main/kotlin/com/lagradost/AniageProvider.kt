package com.lagradost

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
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
    override var mainUrl = "https://aniage.net"
    override var name = "Aniage"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    private val apiUrl = "https://master.api.aniage.net"
    private val findUrl = "https://finder-master.api.aniage.net/?query="
    private val cdnUrl = "https://aniage.fra1.cdn.digitaloceanspaces.com/main/"
    private val pageSize = 30

    private val listEpisodeModel = object : TypeToken<List<EpisodesModel>>() { }.type
    private val listTeamsModel = object : TypeToken<List<TeamsModel>>() { }.type
    private val listPageModel = object : TypeToken<List<PageProps>>() { }.type

    // Sections
    override val mainPage = mainPageOf(
        mainUrl to "Нове",
    )

    // Done
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Log.d("CakesTwix-Debug", page.toString())
        val body = JSONObject()
        body.put("cleanup", JSONArray())
        val orderBody = JSONObject()
        orderBody.put("by", "lastUpdated")
        orderBody.put("direction", "DESC")

        body.put("order", orderBody)
        body.put("page", page)
        body.put("pageSize", pageSize)

        val document = app.post("$apiUrl/v2/anime/find",
            json = body
        ).text
        val parsedJSON = Gson().fromJson(document, FindModel::class.java)
        // Log.d("CakesTwix-Debug", parsedJSON.data[0].title)
        
        val homeList = parsedJSON.data.map {
            newAnimeSearchResponse(it.title, it.id, TvType.Anime) {
                this.posterUrl = "$cdnUrl${it.posterId}"
                addDubStatus(isDub = true, it.episodes)
                this.otherName = it.alternativeTitle
            }
        }
        // Log.d("CakesTwix-Debug", "$cdnUrl${parsedJSON.data[1].posterId}")
        return newHomePageResponse(request.name, homeList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val animeJSON = Gson().fromJson<List<PageProps>>(app.get("$findUrl$query").text, listPageModel)
        val findList = animeJSON.map {
            newAnimeSearchResponse(it.title, it.id, TvType.Anime) {
                this.posterUrl = "$cdnUrl${it.posterId}"
                addDubStatus(isDub = true, it.episodes)
                this.otherName = it.alternativeTitle
            }
        }
        return findList
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val animeID = url.replace("$mainUrl/", "")
        val document = app.get("$mainUrl/watch/$animeID").document
        val jsonObject = JSONObject(document.selectFirst("script[type*=application/json]")!!.html())
        val buildId = jsonObject.getString("buildId")

        // https://www.aniage.net/_next/data/F64n_RAvOkYPvB3Z9Bmw2/watch/fea3c510-f42d-4a18-b438-bfab102f4424.json
        // Log.d("CakesTwix-Debug", app.get("$mainUrl/_next/data/$buildId/watch/$animeID.json").text)
        val animeJSON = Gson().fromJson(app.get("$mainUrl/_next/data/$buildId/watch/$animeID.json").text, AnimeDetail::class.java)

        // Log.d("CakesTwix-Debug", animeJSON.pageProps.title)

        val showStatus = with(animeJSON.pageProps.titleStatus){
            when{
                contains("Онгоїнг") -> ShowStatus.Ongoing
                contains("Вийшло") -> ShowStatus.Completed
                else -> null
            }
        }

        val tvType = with(animeJSON.pageProps.type){
            when{
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

        // Episodes
        // https://master.api.aniage.net/anime/episodes
        // ?animeId=2c60c269-049e-428b-96ba-fae23ac718ec
        // &page=1
        // &pageSize=30
        // &sortOrder=ASC
        // &teamId=99012182-f177-45df-a21f-6823bb9955c3
        // &volume=1

        val episodes = mutableListOf<Episode>()

        // Log.d("CakesTwix-Debug", app.get("https://master.api.aniage.net/anime/episodes?animeId=$animeID&page=1&pageSize=30&sortOrder=ASC&teamId=${teams.teamId}&volume=1").url)
        if(animeJSON.pageProps.teams.isNotEmpty()){
            Gson().fromJson<List<EpisodesModel>>(app.get("https://master.api.aniage.net/anime/episodes?animeId=$animeID&page=1&pageSize=30&sortOrder=ASC&teamId=${animeJSON.pageProps.teams[0].teamId}&volume=1").text, listEpisodeModel).map {
                episodes.add(Episode
                    (
                    "${it.animeId}, ${it.episodeNum}",
                    "Серія ${it.title}",
                    it.volume,
                    it.episodeNum,
                    "$cdnUrl${it.previewPath}",
                )
                )
            }
        }


        return newAnimeLoadResponse(
            animeJSON.pageProps.title,
            "$mainUrl/watch/$animeID",
            tvType,
        ) {
            this.posterUrl = "$cdnUrl${animeJSON.pageProps.posterId}"
            this.engName = animeJSON.pageProps.alternativeTitle
            this.tags = animeJSON.pageProps.genres.map { it }
            this.plot = animeJSON.pageProps.description
            // addTrailer(animeJSON.pageProps.trailerUrl)
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

        val animeJSON = Gson().fromJson(app.get("$mainUrl/_next/data/$buildId/watch/${dataList[0]}.json").text, AnimeDetail::class.java)

        // Parse list, by episode
        animeJSON.pageProps.teams.map { teams ->
            val TeamsList = Gson().fromJson<List<TeamsModel>>(app.get("$apiUrl/anime/teams/by-ids?ids=${teams.teamId}").text, listTeamsModel)[0]
            // Log.d("CakesTwix-Debug", app.get("https://master.api.aniage.net/anime/episodes?animeId=$animeID&page=1&pageSize=30&sortOrder=ASC&teamId=${teams.teamId}&volume=1").url)
            Gson().fromJson<List<EpisodesModel>>(app.get("https://master.api.aniage.net/anime/episodes?animeId=${dataList[0]}&page=1&pageSize=30&sortOrder=ASC&teamId=${teams.teamId}&volume=1").text, listEpisodeModel).map {
                if(it.episodeNum == dataList[1].toInt()){
                    // Log.d("CakesTwix-Debug", app.get(it.playPath).document.select("source[type*=application/x-mpegURL]").attr("src"))
                    M3u8Helper.generateM3u8(
                        source = TeamsList.name,
                        streamUrl = app.get(it.playPath).document.select("source[type*=application/x-mpegURL]").attr("src"),
                        referer = mainUrl
                    ).forEach(callback)
                }
            }
        }
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