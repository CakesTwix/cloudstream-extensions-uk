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

class UAFlixProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://uafix.net"
    override var name = "UAFlix"
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
        "$mainUrl/serials/page/" to "Серіали",
        "$mainUrl/serials/multseial/page/" to "Мультсеріали",
        "$mainUrl/cartoons/page/" to "Мультфільми",
        "$mainUrl/anime/page/" to "Аніме",
        "$mainUrl/dorama/page/" to "Дорами",
        "$mainUrl/film/page/" to "Фільми"
    )

    // Main Page
    private val animeSelector = ".video-item"
    private val titleSelector = ".vi-img"
    // private val engTitleSelector = "div.th-title-oname.truncate"
    private val hrefSelector = titleSelector
    private val posterSelector = ".img-resp-h img"

    // Load info
    // private val titleLoadSelector = ".page__subcol-main h1"
    // private val genresSelector = "li span:contains(Жанр:) a"
    // private val yearSelector = "a[href*=https://uaserials.pro/year/]"
    // private val playerSelector = "iframe"
    private val descriptionSelector = "div[id=serial-kratko]"
    private val ratingSelector = ".mediablock .rat-imdb"

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
        val title = this.selectFirst(titleSelector)?.attr("alt")?.trim().toString()
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

        val title = document.select(".fright h1").text().trim().replace("дивитись онлайн", "")
        val engTitle = document.select("span.eng-rus").text()
        var poster = fixUrl(document.select(".img-box img").attr("data-src"))
        if(poster.isNullOrBlank()){
            poster = fixUrl(document.select(".img-box img").attr("src"))
        }
        val tags = mutableListOf<String>()
        val actors = mutableListOf<String>()
        var year = "f".toIntOrNull()

        document.select(".fcols4 .finfo li").forEach { menu ->
            with(menu){
                when{
                    this.selectFirst("span").text() == "Жанр:" -> menu.select("span[itemprop=genre]").map { tags.add(it.text()) }
                    this.selectFirst("span").text() == "В ролях:" -> menu.select("span[itemprop=actor]").map { actors.add(it.text()) }
                    this.selectFirst("span").text() == "Рік виходу:" -> year = menu.select(".year").text().toIntOrNull()
                }
            }
        }

        var tvType = with(url){
            when{
                contains("serials") -> TvType.TvSeries
                contains("serials/multseial") -> TvType.Cartoon
                contains("film") -> TvType.Movie
                contains("cartoons") -> TvType.Movie
                contains("anime") -> TvType.Anime
                else -> TvType.TvSeries
            }
        }
        val description = document.selectFirst(descriptionSelector)?.text()?.trim()
        val rating = document.select(ratingSelector).text().toRatingInt()

        // Parse episodes
        val episodes = mutableListOf<Episode>()
        val playerUrl = document.select(".video-box iframe").attr("src")
        if(playerUrl.isNullOrBlank()){ // Need parse episode list from site
            val pagination = if (document.select(".pagination li").size == 0) 1 else document.select(".pagination li").size
            for(i in 1..pagination){
                var episodesList = document
                if(i != 1){
                    episodesList = app.get("$url?page=$i").document
                }

                episodesList.select(".video-item").map { video_item ->
                    episodes.add(
                        Episode(
                                video_item.select(".vi-img").attr("href"),
                                video_item.select(".vi-rate").text(),
                                extractIntsFromString(video_item.select(".vi-title").text())[0].value.toIntOrNull(),
                                extractIntsFromString(video_item.select(".vi-title").text())[1].value.toIntOrNull(),
                                posterUrl = fixUrl(video_item.select(".img-resp-h img").attr("data-src"))
                        )
                    )
                }
            }

        } else { // Player in site
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
                addEpisodes(DubStatus.Dubbed, episodes.sortedBy { it.episode })
                addActors(actors)
            }
        } else { // Parse as Movie.

            newMovieLoadResponse(title, url, tvType, url) {
                this.posterUrl = poster
                this.name = engTitle
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
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

        if(dataList.size == 1){
            var playerUrl = app.get(data).document.select(".video-box iframe").attr("src")
            if(playerUrl.startsWith("//")){
                playerUrl = "https:$playerUrl"
            }
            if(playerUrl.contains("/vod/")){
                var playerRawJson = app.get(playerUrl,
                        headers = mapOf(
                                "Referer" to "https://uafix.net/",
                        )).document.select("script").html()
                        .substringAfterLast("file:\"")
                        .substringBefore("\",")

                M3u8Helper.generateM3u8(
                        source = "UAFlix",
                        streamUrl = playerRawJson,
                        referer = "https://tortuga.wtf/"
                ).forEach(callback)
                return true
            }
            val playerRawJson = app.get(playerUrl).document.select("script").html()
                    .substringAfterLast("file:\'")
                    .substringBefore("\',")
            tryParseJson<List<PlayerJson>>(playerRawJson)?.map { dubs ->   // Dubs
                for(season in dubs.folder){                                // Seasons
                    // Add as source
                    M3u8Helper.generateM3u8(
                            source = dubs.title,
                            streamUrl = dubs.folder[0].folder[0].file,
                            referer = "https://tortuga.wtf/"
                    ).forEach(callback)
                }
            }

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
                                    streamUrl = episode.file.replace("https://", "http://"),
                                    referer = "https://tortuga.wtf/"
                            ).forEach(callback)
                        }
                    }
                }
            }
        }

        return true
    }

    private fun extractIntsFromString(string: String): List<MatchResult> {
        return Regex("(\\d+)").findAll(string).toList()
    }
}
