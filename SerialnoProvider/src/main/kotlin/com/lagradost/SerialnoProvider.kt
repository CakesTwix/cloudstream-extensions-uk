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
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.PlayerJson
import org.jsoup.nodes.Element

class SerialnoProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://serialno.tv"
    override var name = "Serialno"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.Cartoon,
        TvType.TvSeries,
        TvType.Anime
    )

    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/series/page/" to "Серіали",
        "$mainUrl/cartoons/page/" to "Мультсеріали",
        "$mainUrl/mini-serials/page/" to "Міні-серіали",

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

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            url = "$mainUrl/",
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

        // General info
        val generalInfo = document.select(".flist li")

        // Parse info
        val title = document.select(".full h1").text()
        val poster = document.select(".fposter a").attr("href")

        val tags = mutableListOf<String>()
        // Can be smaller
        if (generalInfo.size > 4) {
            document.select(".flist li")[4].select("a").map { tags.add(it.text()) }
        } else {
            document.select(".flist li")[3].select("a").map { tags.add(it.text()) }
        }
        val year = document.select(".flist li")[1].select("a").text().toIntOrNull()
        val tvType = TvType.TvSeries
        val description = document.select(".full-text").text()
        // val author = someInfo.select("strong:contains(Студія:)").next().html()
        val rating = document.selectFirst(".th-voice")?.text().toRatingInt()

        // Parse episodes
        val episodes = mutableListOf<Episode>()
        val playerUrl = document.select("div.video-box iframe").attr("src")

        // Return to app
        // Parse Episodes as Series
        val playerRawJson = app.get(playerUrl).document.select("script").html()
            .substringAfterLast("file: \'")
            .substringBefore("\',")

        AppUtils.tryParseJson<List<PlayerJson>>(playerRawJson)?.map { season -> // Dubs
            for (episode in season.folder) {                                     // Seasons
                for (dubs in episode.folder) {                              // Episodes
                    episodes.add(
                        Episode(
                            "${season.title}, ${episode.title}, $playerUrl",
                            episode.title,
                            season.season,
                            episode.number,
                            dubs.poster
                        )
                    )
                }
            }
        }
        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.rating = rating
        }
    }


    // It works when I click to view the series
    override suspend fun loadLinks(
        data: String, // (Serial) [Season, Episode, Player Url]
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split(", ")

        val playerRawJson = app.get(dataList[2]).document.select("script").html()
            .substringAfterLast("file: \'")
            .substringBefore("\',")

        AppUtils.tryParseJson<List<PlayerJson>>(playerRawJson)?.map { season ->
            if(season.title != dataList[0]) return@map

            for (episode in season.folder) {
                if(episode.title != dataList[1]) return@map

                for (dubs in episode.folder) {
                    M3u8Helper.generateM3u8(
                        source = dubs.title,
                        streamUrl = dubs.file,
                        referer = "https://tortuga.wtf/"
                    ).last().let(callback)

                    if(dubs.subtitle.isNullOrBlank()) {
                        subtitleCallback.invoke(
                            SubtitleFile(
                                dubs.subtitle.substringAfterLast("[").substringBefore("]"),
                                dubs.subtitle.substringAfter("]")
                            )
                        )
                    }
                }
            }
        }
        return true
    }
}