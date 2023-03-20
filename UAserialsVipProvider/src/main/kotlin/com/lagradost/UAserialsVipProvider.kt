package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.models.PlayerJson
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Element

class UAserialsVipProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://uaserials.vip"
    override var name = "UAserialsVip"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.TvSeries,
        TvType.Anime
    )

    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/series/page/" to "Серіали",
        "$mainUrl/cartoons/page/" to "Мультфільми",
        "$mainUrl/anime/page/" to "Аніме",

        )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document

        // Delete popular items
        document.select(".sect--top").remove()
        val home = document.select(".poster-item").map {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val title = this.select("div.poster-item__title").text()
        val href = this.select("a.poster-item").attr("href").toString()
        val posterUrl = mainUrl + this.select(".img-responsive > img").attr("data-src")

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

        return document.select(".movie-item").map {
            it.toSearchResponse()
        }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Parse info
        val title = document.select(".page__header h1").text()
        val poster = mainUrl + document.select(".img-fit-cover img").attr("data-src")
        var tags = document.select(".page__meta-item--genres a").map { it.text() }

        val year = document.select(".page__year").text().toIntOrNull()
        val tvType = with(document.select(".page__details .page__subtitle").text()) {
            when {
                contains("мультсеріал") -> TvType.Cartoon
                contains("аніме") -> TvType.Anime // 0 Anime in site, lol
                else -> {
                    TvType.TvSeries
                }
            }
        }

        val description = document.select(".full-text").text()
        // val author = someInfo.select("strong:contains(Студія:)").next().html()
        val rating = document.selectFirst(".page__rating-item--critics div")?.text().toRatingInt()

        val recommendations = document.select(".poster-item").map {
            it.toSearchResponse()
        }

        // Parse episodes
        var episodes: List<Episode> = emptyList()
        val playerUrl = document.select(".video-responsive > iframe").attr("data-src")

        // Return to app
        val playerRawJson = app.get(playerUrl).document.select("script").html()
            .substringAfterLast("file:\'")
            .substringBefore("\',")

        AppUtils.tryParseJson<List<PlayerJson>>(playerRawJson)?.map { dubs -> // Dubs
            for (season in dubs.folder) {                              // Seasons
                for (episode in season.folder) {                       // Episodes
                    episodes = episodes.plus(
                        Episode(
                            "${season.title}, ${episode.title}, $playerUrl",
                            episode.title,
                            season.title.replace(" Сезон ", "").toIntOrNull(),
                            episode.title.replace("Серія ", "").toIntOrNull(),
                            episode.poster
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
            this.recommendations = recommendations
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
            ).forEach(callback)

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
                            ).forEach(callback)
                        }
                    }
                }
            }
        }
        return true
    }
}
