package com.lagradost

import android.util.Log
import com.google.gson.Gson
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Element

class BambooUAProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://bambooua.com"
    override var name = "BambooUA"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AsianDrama,
    )

    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/dorama/page/" to "Дорама",
        "$mainUrl/anime/page/" to "Аніме",
        "$mainUrl/lakorn/page/" to "Лакорн",
        "$mainUrl/cinema/page/" to "Кіно",
        "$mainUrl/voice/page/" to "Озвучення",
        "$mainUrl/tv-show/page/" to "ТВ-шоу",
        "$mainUrl/done/page/" to "Завершені",
        "$mainUrl/world-bl/page/" to "Світ ЛГБТ",
        "$mainUrl/now/page/" to "Поточні",
    )

    // Main Page
    private val animeSelector = "li.slide-item"
    private val titleSelector = ".block-description > h6"
    private val hrefSelector = ".block-images .hover-buttons"
    private val posterSelector = ".img-fluid"

    // Load info
    // private val titleLoadSelector = ".movie-detail .trending-info .trending-text"
    private val genresSelector = "span.full_cat a"
    private val yearSelector = ".trending-info .text-detail span.badge-danger"
    // private val playerSelector = ".video-responsive > iframe"
    // private val descriptionSelector = ".full-text"
    // private val ratingSelector = ".pmovie__subrating img"

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document

        val home = document.select(animeSelector).map {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): AnimeSearchResponse {
        val title = this.selectFirst(titleSelector)?.text()?.trim().toString()
        val href = this.selectFirst(hrefSelector)?.attr("href").toString()
        val posterUrl = mainUrl + this.selectFirst(posterSelector)?.attr("src")


        val sub = this.select(".Type_project_SUB").text().substringAfter(". ")
        val dub = this.select(".Type_project_DUB").text().substringAfter(". ")
        // Log.d("load-debug", dub)
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(dub.isNotEmpty(), sub.isNotEmpty(), dub.toIntOrNull(), sub.toIntOrNull())
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            url = mainUrl,
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to query.replace(" ", "+")
            )
        ).document

        return document.select(animeSelector).map {
            it.toSearchResponse()
        }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        // Parse info
        // val json = tryParseJson<JSONModel>(document.select("script[type*=json]").html())
        val gJson = Gson().fromJson(document.select("script[type*=json]").html(), JSONModel::class.java)
        Log.d("load-debug-json", gJson.graph[0].name)

        val title = gJson.graph[0].name

        val poster = gJson.graph[0].image[1]
        val tags = document.select(genresSelector).map { it.text() }
        val year = document.select(yearSelector).text().toIntOrNull()

        val tvType = with(tags){
            when{
                contains("Аніме") -> TvType.Anime
                contains("Кіно") -> TvType.Movie
                else -> TvType.AsianDrama
            }
        }
        val description = gJson.graph[0].description
        // val rating = document.select(ratingSelector).next().text().toRatingInt()

        val recommendations = document.select(".favorites-slider li.slide-item").map {
            it.toSearchResponse()
        }

        var subEpisodes: List<Episode> = emptyList()
        var dubEpisodes: List<Episode> = emptyList()

        // Parse episodes (sub/dub)
        document.select(".mt-4").forEach {
            // Parse sub
            if(it.select("h3.my-4").text() == "Субтитри"){
                it.select("span.play_me").forEach{ episode ->
                    subEpisodes = subEpisodes.plus(
                        Episode(
                            episode.attr("data-file"),
                            episode.attr("data-title"),
                            episode = episode.attr("data-title").replace("Серія ","").toIntOrNull(),
                        )
                    )
                }
                // Parse dub
            } else if(it.select("h3.my-4").text() == "Озвучення"){
                it.select("span.play_me").forEach{ episode ->
                    dubEpisodes = dubEpisodes.plus(
                        Episode(
                            episode.attr("data-file"),
                            episode.attr("data-title"),
                            episode = episode.attr("data-title").replace("Серія ","").toIntOrNull(),
                        )
                    )
                }
            }
        }

        // Return to app
        // Parse Episodes as Series
        return if(tvType != TvType.Movie){
            newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                this.addEpisodes(DubStatus.Dubbed, dubEpisodes)
                this.addEpisodes(DubStatus.Subbed, subEpisodes)
            }
        } else{
            newMovieLoadResponse(title, url, tvType, url) {
                this.posterUrl = poster
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
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // Movie
        if(data.startsWith("https://bambooua.com")){
            val document = app.get(data).document
            document.select("span.mr-3").forEach {
                Log.d("load-debug", it.attr("data-file"))
                M3u8Helper.generateM3u8(
                    source = it.attr("data-title"),
                    streamUrl = it.attr("data-file"),
                    referer = ""
                ).forEach(callback)
            }
            return true
        }

        // Serial
        M3u8Helper.generateM3u8(
            source = "Bambooua",
            streamUrl = data,
            referer = ""
        ).forEach(callback)

        return true
    }

}
