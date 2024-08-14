package com.lagradost

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
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
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.PlayerJson
import org.jsoup.nodes.Element

class VodneriloProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://vodnerilo.com"
    override var name = "Vodnerilo"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Cartoon,
        TvType.Movie,
        TvType.Anime
    )

    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/serialy/page/" to "Серіали",
        "$mainUrl/multserialy/page/" to "Мультсеріали",
        "$mainUrl/multfilmy/page/" to "Мультфільми",
        "$mainUrl/anime/page/" to "Аніме",
        "$mainUrl/filmy/page/" to "Фільми"
    )

    // Main Page
    private val animeSelector = ".short-item"
    private val titleSelector = ".short-title"
    // private val engTitleSelector = "div.th-title-oname.truncate"
    private val hrefSelector = ".short-title"
    private val posterSelector = "img"

    // Load info
    // private val titleLoadSelector = ".page__subcol-main h1"
    // private val genresSelector = "li span:contains(Жанр:) a"
    // private val yearSelector = "a[href*=https://uaserials.pro/year/]"
    // private val playerSelector = "iframe"
    private val descriptionSelector = ".full-text"
    private val ratingSelector = ".mrating .unit-rating li.current-rating"

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

        val title = document.select(".short-title").text().trim().toString()
        val engTitle = document.select(".pmovie__original-title").text()
        val poster = fixUrl(document.select("div.img-wide img").attr("src"))
        val tags = mutableListOf<String>()
        val actors = mutableListOf<String>()
        var year = "0".toRatingInt()

        document.select(".short-list li").forEach { menu ->
            with(menu){
                when{
                    this.select("span").text() == "Жанр:" -> menu.ownText().split(", ").map { tags.add(it) }
                    this.select("span").text() == "У ролях:" -> menu.ownText().split(", ").map { actors.add(it) }
                    this.select("span").text() == "Рік виходу:" -> year = menu.ownText().toIntOrNull()
                }
            }
        }

        var tvType = with(url){
            when{
                contains("serialy") -> TvType.TvSeries
                contains("multserialy") -> TvType.Cartoon
                contains("filmy") -> TvType.Movie
                contains("multfilmy") -> TvType.Movie
                contains("anime") -> TvType.Anime
                else -> TvType.TvSeries
            }
        }
        val description = document.selectFirst(descriptionSelector)?.text()?.trim()
        val rating = document.select(ratingSelector).next().text().toRatingInt()

        // TODO: Fix
        val recommendations = document.select("div.owl-item").map {
            val title = it.select(".popular-item-title").text().trim().toString()
            // val engTitle = this.selectFirst(engTitleSelector)?.text()?.trim().toString()
            val href = it.select("a.popular-item-img").attr("href").toString()
            val posterUrl = fixUrl(it.select(".img-fit img").attr("src"))

            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                // this.otherName = engTitle
                this.posterUrl = posterUrl
                addDubStatus(isDub = true)
            }
        }

        // Parse episodes
        val episodes = mutableListOf<Episode>()
        val playerUrl = document.select("div.video-box iframe").attr("src")
        if(playerUrl.contains("/serial/") && tvType == TvType.Movie) tvType = TvType.TvSeries
        if(playerUrl.contains("/vod/") && tvType != TvType.Movie) tvType = TvType.Movie

        // Parse Episodes as Series
        return if (tvType != TvType.Movie) {
            val playerRawJson = app.get(playerUrl).document.select("script").html()
                .substringAfterLast("file:\'")
                .substringBefore("\',")

            tryParseJson<List<PlayerJson>>(playerRawJson)?.map { dubs -> // Dubs
                for(season in dubs.folder){                              // Seasons
                    for(episode in season.folder){                       // Episodes
                        episodes.add(
                            Episode(
                                "${season.title}, ${episode.title}, $playerUrl",
                                episode.title,
                                season.title.replace(" Сезон ","").toIntOrNull(),
                                episode.title.replace("Серія ","").toIntOrNull(),
                                episode.poster
                            )
                        )
                    }
                }
            }
            newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = poster
                this.engName = engTitle
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                addEpisodes(DubStatus.Dubbed, episodes)
                addActors(actors)
            }
        } else { // Parse as Movie.

            newMovieLoadResponse(title, url, tvType, "$title, $playerUrl") {
                this.posterUrl = poster
                this.name = engTitle
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
            }
        }
    }

    // It works when I click to view the series
    override suspend fun loadLinks(
        data: String, // (Serial) [Season Index, Episode Name, url] | (Film) [Title, Player Url]
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
            ).last().let(callback)

            return true
        }

        val playerRawJson = app.get(dataList[2]).document.select("script").html()
            .substringAfterLast("file:\'")
            .substringBefore("\',")

        AppUtils.tryParseJson<List<PlayerJson>>(playerRawJson)?.map { dubs ->   // Dubs
            for(season in dubs.folder){                                // Seasons
                if(season.title == dataList[0]){
                    for(episode in season.folder){                     // Episodes
                        if(episode.title == dataList[1]){
                            // Add as source
                            M3u8Helper.generateM3u8(
                                source = dubs.title,
                                streamUrl = episode.file,
                                referer = "https://tortuga.wtf/"
                            ).last().let(callback)
                        }
                    }
                }
            }
        }
        return true
    }
}
