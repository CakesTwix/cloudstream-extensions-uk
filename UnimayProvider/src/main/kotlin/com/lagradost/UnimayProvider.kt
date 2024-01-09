package com.lagradost

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
    private val imagesUrl = "$apiUrl/storage/images/"

    // Sections
    override val mainPage = mainPageOf(
        "$apiUrl/api/release/all?page=" to "Останні релізи",
    )

    // Done
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val homeList = app.get("${request.data}$page").parsedSafe<Releases>()!!.releases.map{
            newAnimeSearchResponse(it.name, "$apiUrl/api/release/${it.code}", TvType.Anime) {
                this.posterUrl = "$imagesUrl${it.imageId}"
                addDubStatus("${it.playlistSize}/${it.episodes}", it.playlistSize)
            }
        }

        return newHomePageResponse(request.name, homeList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$findUrl$query&page=1").parsedSafe<Releases>()!!.releases.map{
            newAnimeSearchResponse(it.name, "$apiUrl/api/release/${it.code}", TvType.Anime) {
                this.posterUrl = "$imagesUrl${it.imageId}"
                addDubStatus("${it.playlistSize}/${it.episodes}", it.playlistSize)
            }
        }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        // Log.d("CakesTwix-Debug", url)
        val anime = app.get(url).parsedSafe<SearchModel>()!!
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


        val episodes = anime.playlist.map{
            Episode(
                "${anime.code}, ${it.number}",
                it.title,
                episode = it.number,
                posterUrl = if(it.previewId != null) { "$imagesUrl${it.previewId}" } else null,
            )
        }

        return newAnimeLoadResponse(
            anime.name,
            "$mainUrl/projects/${anime.code}",
            tvType,
        ) {
            this.engName = anime.engName
            this.posterUrl = "$imagesUrl${anime.posterId}"
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
        // Log.d("CakesTwix-Debug", dataList.toString())

        val anime = app.get("$apiUrl/api/release/${dataList[0]}").parsedSafe<SearchModel>()!!
        // val anime = Gson().fromJson(app.get("$apiUrl/api/release/${dataList[0]}").text, SearchModel::class.java)
        val episode = anime.playlist.first { it.number == dataList[1].toInt() }

        if (episode.playlist != null) {
            M3u8Helper.generateM3u8(
                source = "Unimay",
                streamUrl = episode.playlist,
                referer = "https://www.unimay.media"
            ).forEach(callback)
            return true
        }

        return true
    }
}
