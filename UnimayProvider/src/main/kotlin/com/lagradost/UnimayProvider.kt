package com.lagradost

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
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
import com.lagradost.models.Releases
import com.lagradost.models.SearchModel
import com.lagradost.models.Updates

class UnimayProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://www.unimay.media"
    override var name = "Unimay"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
    )

    private val apiUrl = "https://api.unimay.media"
    private val findUrl = "$apiUrl/v1/release/search?title="
    private val imagesUrl = "https://img.unimay.media/"

    private val TAG = name
    private val listUpdatesModel = object : TypeToken<List<Updates>>() { }.type

    // Sections
    override val mainPage = mainPageOf(
        "$apiUrl/v1/list/series/updates?size=15" to "Останні релізи",
        "$apiUrl/v1/release/search?page_size=10&page=" to "Наші проєкти",
    )

    // Done
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page != 1 && request.data.contains("updates")) return HomePageResponse(emptyList())
        if (request.data.contains("updates")){
            val homeList = Gson().fromJson<List<Updates>>(app.get("${request.data}").text, listUpdatesModel).map{
                newAnimeSearchResponse(it.release.name, "$apiUrl/v1/release?code=${it.release.code}", TvType.Anime) {
                    this.posterUrl = "$imagesUrl${it.release.posterUuid}?width=640&format=webp"
                    addDubStatus(DubStatus.Dubbed, it.series.number)
                }
            }
            return newHomePageResponse(request.name, homeList)
        }

        val homeList = Gson().fromJson(app.get("${request.data}${page}").text, SearchModel::class.java).content.map{
            newAnimeSearchResponse(it.names.ukr, "$apiUrl/v1/release?code=${it.code}", TvType.Anime) {
                this.posterUrl = "$imagesUrl${it.images.poster}?width=640&format=webp"
                addDubStatus(DubStatus.Dubbed, it.playlistSize)
            }
        }
        return newHomePageResponse(request.name, homeList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return Gson().fromJson(app.get("$findUrl$query&page=0").text, SearchModel::class.java).content.map{
            newAnimeSearchResponse(it.names.ukr, "$apiUrl/v1/release?code=${it.code}", TvType.Anime) {
                this.posterUrl = "$imagesUrl${it.images.poster}?width=640&format=webp"
                addDubStatus("${it.playlistSize}/${it.playlistSize}", it.playlistSize)
            }
        }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        var ApiUrl = url
        if (url.contains("/projects/")) {
            ApiUrl = "$apiUrl/v1/release?code=${url.substringAfterLast("/")}"
        }
        val anime = Gson().fromJson(app.get(ApiUrl).text, Releases::class.java)
        // val anime = Gson().fromJson(app.get("$apiUrl/api/release/${url.substringAfterLast("/")}").text, SearchModel::class.java)

        val showStatus = when(anime.statusCode){
            0 -> ShowStatus.Ongoing
            else -> ShowStatus.Completed

        }

        val tvType = when(anime.type){
            "Фільм" -> TvType.AnimeMovie
            "Телесеріал" -> TvType.Anime
            else -> TvType.Anime
        }

        val episodes = mutableListOf<Episode>()

        anime.playlist.forEach{
            if (it.premium) return@forEach
            episodes.add(
                Episode
                    (
                    "${anime.code}, ${it.number}",
                    it.title,
                    episode = it.number,
                    posterUrl = if(it.imageUuid != null) { "$imagesUrl${it.imageUuid}" } else null,
                )
            )
        }

        return newAnimeLoadResponse(
            anime.names.ukr,
            "$mainUrl/projects/${anime.code}",
            tvType,
        ) {
            this.engName = anime.names.eng
            this.posterUrl = "$imagesUrl${anime.images.banner}?width=1440&format=webp"
            this.tags = anime.genres
            this.plot = anime.description
            this.showStatus = showStatus
            this.duration = anime.episodeLength
            addEpisodes(DubStatus.Dubbed, episodes)
            this.year = anime.year
            addAniListId(anime.aniListId)
        }
    }

    // It works when I click to view the series
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // anime_id, episode number
        val dataList = data.split(", ")
        val anime = Gson().fromJson(app.get("$apiUrl/v1/release?code=${dataList[0]}").text, Releases::class.java)
        val episode = anime.playlist.first { it.number == dataList[1].toInt() }

        if (episode.hls != null) {
            M3u8Helper.generateM3u8(
                source = "Unimay",
                streamUrl = episode.hls.master,
                referer = "https://www.unimay.media"
            ).forEach(callback)
            return true
        }

        return true
    }
}
