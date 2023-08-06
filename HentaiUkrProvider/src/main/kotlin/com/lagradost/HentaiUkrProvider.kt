package com.lagradost

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.models.CfgModel
import com.lagradost.models.ObjectsModel

class HentaiUkrProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://hentaiukr.com"
    override var name = "HentaiUkr 18+"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW,
    )

    private val listCfgJSONModel = object : TypeToken<List<CfgModel>>() { }.type

    // Sections
    override val mainPage = mainPageOf(
        "https://hentaiukr.com/search/objects.json" to "\uD83D\uDD1E Хентай",
    )

    // Done
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).text
        // Log.d("CakesTwix-Debug", document)
        val parsedJSON = Gson().fromJson(document, ObjectsModel::class.java).video

        
        val homeList = parsedJSON.map {
            newAnimeSearchResponse(it.name, "$mainUrl${it.url}", TvType.NSFW) {
                this.posterUrl = "$mainUrl${it.thumb}"
                this.otherName = it.orig_name
            }
        }
        // Log.d("CakesTwix-Debug", "$cdnUrl${parsedJSON.data[1].posterId}")
        return newHomePageResponse(request.name, homeList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val parsedJSON = Gson().fromJson<List<CfgModel>>(app.get(url + "plur.cfg.json").text, listCfgJSONModel)
        val document = app.get(url).document


        //Log.d("CakesTwix-Debug", document.select("div[id*=year]").text().substringAfterLast(": "))
        val episodes = mutableListOf<Episode>()
        parsedJSON.forEachIndexed { index, cfgModel ->
            episodes.add(
                Episode(
                    "$url, $index",
                    "Серія ${index + 1}",
                    episode = index + 1,
                    posterUrl = "$mainUrl${cfgModel.poster}"
                )
            )
        }
        return newTvSeriesLoadResponse(
            document.select("#name-ukr").text(), url, TvType.NSFW, episodes
        ){
            this.posterUrl = "$mainUrl${document.select("#img-placeholder img").attr("src")}"
            this.plot = document.select("#about").text()
            this.year = document.select("div[id*=year]").text().substringAfterLast(": ").toIntOrNull()
            this.tags = document.select("div.tag").map { it.select("div.name").text() }
        }
    }

    // It works when I click to view the series
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split(", ")

        val parsedJSON = Gson().fromJson<List<CfgModel>>(app.get("${dataList[0]}plur.cfg.json").text, listCfgJSONModel)[dataList[1].toInt()]
        parsedJSON.sources.forEach {
            // Add as source
            val quality = with(it.size){
                when{
                    this == 1080 -> Qualities.P1080.value
                    this == 720 -> Qualities.P720.value
                    this == 480 -> Qualities.P480.value
                    else -> Qualities.Unknown.value
                }
            }
            callback(ExtractorLink("${dataList[0]}${it.src}","Серія ${dataList[1] + 1}", "${dataList[0]}${it.src}",
                dataList[0],
                quality, false))
        }
        return true
    }
}
