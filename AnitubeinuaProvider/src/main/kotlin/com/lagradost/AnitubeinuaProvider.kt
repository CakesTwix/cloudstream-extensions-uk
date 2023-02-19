package com.lagradost

import android.util.Log
import com.lagradost.models.PlayerJson
import com.lagradost.extractors.AshdiExtractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnitubeinuaProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://anitube.in.ua"
    override var name = "Anitubeinua"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.Anime,
    )

    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/anime/page/" to "Нові",
        "$mainUrl/f/sort=rating/order=desc/page/" to "Популярне",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        var document = app.get(request.data + page).document

        val home = document.select(".story").map {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val title = this.selectFirst(".story_c h2 a")?.text()?.trim().toString()
        val href = this.selectFirst(".story_c h2 a")?.attr("href").toString()
        val posterUrl = mainUrl + this.selectFirst(".story_c_l span.story_post img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            url = "$mainUrl",
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to query.replace(" ", "+")
            )
        ).document

        return document.select("article.short").map {
            it.toSearchResponse()
        }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val someInfo = document.select("div.story_c_r")[1]

        // Parse info
        val title = document.selectFirst(".story_c h2")?.text()?.trim().toString()
        val poster = mainUrl + document.selectFirst(".story_c_left span.story_post img")?.attr("src")
        val tags = someInfo.select("noindex a").html().split("\n").map { it }
        val year = someInfo.select("strong:contains(Рік випуску аніме:)").next().html().toIntOrNull()

        val tvType = TvType.Anime
        val description = document.selectFirst("div.my-text")?.text()?.trim()
        // val author = someInfo.select("strong:contains(Студія:)").next().html()
        val rating = document.selectFirst(".lexington-box > div:last-child span")?.text().toRatingInt()

        val recommendations = document.select(".horizontal ul").map {
            it.toSearchResponse()
        }

        // Return to app
        // 12 - json with episodes
        // Players, Episodes, Number of episodes
        // TODO: Parse Episodes
        var episodes: List<Episode> = emptyList()

        document.select("script").map{ script ->
            if (script.data().contains("RalodePlayer.init(")) {
                val playerScriptRawJson = script.data().substringAfterLast(".init(").substringBefore(");")
                val playerEpisodesRawJson = playerScriptRawJson.substringAfter("],").substringBeforeLast(",")
                val playerNamesArray = (playerScriptRawJson.substringBefore("],") + "]").dropLast(1).drop(1).split(",")
                val numberOfEpisodesInt = playerScriptRawJson.substringAfterLast(",").toIntOrNull()

                // Decoded string, thanks to Secozzi
                // val hexRegex = Regex("\\\\u([0-9a-fA-F]{4})")
                // val decodedString = hexRegex.replace(playerNamesArray[0]) { matchResult ->
                //     Integer.parseInt(matchResult.groupValues[1], 16).toChar().toString()
                // }
                // Log.d("load-debug",  decodedString)

                val playerJson = tryParseJson<List<List<PlayerJson>>>(playerEpisodesRawJson)!!
                for(item in playerJson) {
                    for (item2 in item) {
                        if(!item2.name.contains("ПЛЕЙЛИСТ")) // UFDub player
                        {
                            episodes = episodes.plus(
                                Episode(
                                    "${item2.name}, $url",
                                    item2.name,
                                    episode = item2.name.replace("Серія ","").toIntOrNull(),
                                )
                            )
                        }
                    }
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
        data: String, // Серія, url title
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split(", ")
        val document = app.get(dataList[1]).document
        Log.d("load-debug",  dataList.toString())

        document.select("script").map { script ->
            Log.d("load-debug",  script.data())
            if (script.data().contains("RalodePlayer.init(")) {
                val playerScriptRawJson = script.data().substringAfterLast(".init(").substringBefore(");")
                val playerEpisodesRawJson = playerScriptRawJson.substringAfter("],").substringBeforeLast(",")
                Log.d("load-debug",  playerScriptRawJson)

                val playerJson = tryParseJson<List<List<PlayerJson>>>(playerEpisodesRawJson)!!
                for(item in playerJson) {
                    for (item2 in item) {
                        if(item2.name == dataList[0]){
                            if(item2.code.contains("https://ashdi.vip")){
                                M3u8Helper.generateM3u8(
                                    source = dataList[0],
                                    streamUrl = AshdiExtractor().ParseM3U8(Jsoup.parse(item2.code).select("iframe").attr("src")),
                                    referer = "https://qeruya.cyou"
                                ).forEach(callback)
                            }
                        }
                    }
                }
                return true
            }
        }
        return true
    }

    fun decode(input: String): String = java.net.URLDecoder.decode(input, "ISO-8859-1")
}