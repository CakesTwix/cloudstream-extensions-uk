package com.lagradost

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.PlayerJson
import org.jsoup.nodes.Element

class HigoTVProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://higotv.fun"
    override var name = "HigoTV"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime
    )

    private val searchUrl = "https://higotv.fun/search/doSearch"

    // Sections
    override val mainPage = mainPageOf(
        "vsevishlo" to "Виходить, Вийшло",
    )

    // Main Page
    private val animeSelector = "div.poster"
    private val titleSelector = ".greed-item__title_p"
    // private val engTitleSelector = "div.th-title-oname.truncate"
    private val hrefSelector = "a:contains(Перейти)"
    private val posterSelector = ".poster > img"

    // Load info
    // private val titleLoadSelector = ".page__subcol-main h1"
    // private val genresSelector = "li span:contains(Жанр:) a"
    // private val yearSelector = "a[href*=https://uaserials.pro/year/]"
    // private val playerSelector = "iframe"
    private val descriptionSelector = ".anim-txt-all"
    private val ratingSelector = ".rt-tb"

    private val listPlayer = object : TypeToken<List<PlayerJson>>() { }.type

    private val TAG = "$name-Debug"

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}").document

        val home = document.select(animeSelector).map {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): AnimeSearchResponse {
        val title = this.select(titleSelector).text().replace("Назва: ", "")
        // val engTitle = this.selectFirst(engTitleSelector)?.text()?.trim().toString()
        val href = this.selectFirst(hrefSelector)?.attr("href").toString()
        val posterUrl = fixUrl(this.select(posterSelector).attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            // this.otherName = engTitle
            this.posterUrl = posterUrl
            addDubStatus(isDub = true)
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            url = searchUrl,
            data = mapOf("keyword" to query)
        ).document

        return document.select(animeSelector).map {
            it.toSearchResponse()
        }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        // Parse info

        val title = document.select(".anime-op__txt").text()
        val engTitle = document.select(".anime-eng__txt").text()
        val poster = fixUrl(document.select(".anime-op__img > img").attr("src"))
        val tags = document.select(".span-menu-ogg a").map { it.text() }

        val tvType = with(document.select(".tup-anim").text()){
            when{
                contains("TV SHORT") -> TvType.OVA
                contains("OVA") -> TvType.OVA
                contains("Фільм") -> TvType.Movie
                else -> TvType.Anime
            }
        }
        val description = document.selectFirst(descriptionSelector)?.text()?.trim()
        val rating = extractIntFromString(document.select(ratingSelector).text()).toString().toRatingInt()

        // TODO: Fix
        val recommendations = document.select("div.owl-item").map {
            val title = it.select(".popular-item-title").text().trim()
            // val engTitle = this.selectFirst(engTitleSelector)?.text()?.trim().toString()
            val href = it.select("a.popular-item-img").attr("href").toString()
            val posterUrl = fixUrl(it.select(".img-fit img").attr("src"))

            newAnimeSearchResponse(title, href, tvType) {
                this.otherName = engTitle
                this.posterUrl = posterUrl
                addDubStatus(isDub = true)
            }
        }

        // Parse episodes
        val episodes = mutableListOf<Episode>()

        val playerRawJson = document.select("div.player").select("script").html()
            .substringAfterLast("file:")
            .substringBeforeLast("},")

        val parsedJSON = Gson().fromJson<List<PlayerJson>>(playerRawJson, listPlayer)

        parsedJSON[1].folder?.forEach { voices ->
            if(voices != null){
                if(!voices.title.isNullOrEmpty()){
                    voices.folder?.forEach {
                        if(it != null) {
                            if (!it.file.isNullOrBlank()){
                                episodes.add(
                                    Episode(
                                        "${url}, ${it.title}",
                                        it.title,
                                        episode = it.title!!.replace(" Серія","").toIntOrNull(),
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        // Parse Episodes as Series
        return if (tvType != TvType.Movie) {

            newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = poster
                this.engName = engTitle
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.recommendations = recommendations
                addEpisodes(DubStatus.Dubbed, episodes)
            }
        } else { // Parse as Movie.

            newMovieLoadResponse(title, url, tvType, "$title, ") {
                this.posterUrl = poster
                this.name = engTitle
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.recommendations = recommendations
            }
        }
    }

    // It works when I click to view the series
    override suspend fun loadLinks(
        data: String, // (Serial) [Voice name, Series name]
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks: $data")
        val dataList = data.split(", ")

        val document = app.get(dataList[0]).document

        val playerRawJson = document.select("div.player").select("script").html()
            .substringAfterLast("file:")
            .substringBeforeLast("},")

        val parsedJSON = Gson().fromJson<List<PlayerJson>>(playerRawJson, listPlayer)

        // Sorry for this...
        // Here we check for the presence of letters in JSON voices and the presence of folder
        // in them and the scheme is repeated, but further instead of folder, we check file
        parsedJSON[1].folder?.forEach { voices ->
            if(voices != null){
                if(!voices.title.isNullOrEmpty()){
                    voices.folder?.forEach {
                        if(it != null) {
                            if (!it.file.isNullOrBlank()){
                                if (it.title == dataList[1]){
                                    callback.invoke(
                                        ExtractorLink(
                                            it.file,
                                            name = "${voices.title}",
                                            it.file,
                                            "",
                                            0,
                                            isM3u8 = false,
                                        )
                                    )
                                }
                            }
                        }
                    }
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
