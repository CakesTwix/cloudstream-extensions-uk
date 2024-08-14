package com.lagradost

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.PlayerJson
import org.jsoup.nodes.Element

class KinoTronProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://kinotron.top"
    override var name = "KinoTron"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.Cartoon,
        TvType.TvSeries,
        TvType.Anime
    )

    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/serials/page/" to "Серіали",
        "$mainUrl/films/page/" to "Фільми",
        "$mainUrl/cartoons/page/" to "Мультфільми",
        "$mainUrl/cartoon-series/page/" to "Мультсеріали",
        "$mainUrl/anime/page/" to "Аніме",

        )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document

        val home = document.select(".th-item").map {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val title = this.select(".th-title").text()
        val href = this.select(".th-in").attr("href")
        val posterUrl = mainUrl + this.select(".img-fit img").attr("data-src")

        return newMovieSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            url = "$mainUrl/index.php?do=search",
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to query.replace(" ", "+")
            )
        ).document

        return document.select(".th-item").map {
            it.toSearchResponse()
        }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document


        // Parse info
        val title = document.select(".full h1").text()
        val poster = mainUrl + document.select(".img-box img").attr("data-src")
        val tags = document.select(".flist li")[2].select("a").map { it.text() }

        val year = document.select(".flist li")[0].select("a").text().toIntOrNull()

        var tvType = with(document.select(".fsubtitle").text()) {
            when {
                contains("аніме") -> TvType.Anime
                contains("серіал") -> TvType.TvSeries
                contains("мультсеріал") -> TvType.Cartoon
                else -> {
                    TvType.Movie
                }
            }
        }
        val description = document.select(".full-text").text()
        // val author = someInfo.select("strong:contains(Студія:)").next().html()
        val rating = document.selectFirst(".fqualityimdb")?.text().toRatingInt()

        // Parse episodes
        val episodes = mutableListOf<Episode>()
        val playerUrl = document.select("div.video-box iframe").attr("data-src")
        if (playerUrl.contains("/vod/")) { tvType = TvType.Movie }
        // Log.d("load-debug", playerUrl)
        // Return to app
        // Parse Episodes as Series
        return if (tvType == TvType.TvSeries || tvType == TvType.Anime) {
            val playerRawJson = app.get(playerUrl).document.select("script").html()
                .substringAfterLast("file:\'")
                .substringBefore("\',")

            AppUtils.tryParseJson<List<PlayerJson>>(playerRawJson)?.map { dubs -> // Dubs
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
            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
            }
        } else { // Parse as Movie.
            newMovieLoadResponse(title, url, TvType.Movie, "$title, $playerUrl") {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
            }
        }
    }


    // It works when I click to view the series
    override suspend fun loadLinks(
        data: String, // (Serisl) [Season, Episode, Player Url] | (Film) [Title, Player Url]
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