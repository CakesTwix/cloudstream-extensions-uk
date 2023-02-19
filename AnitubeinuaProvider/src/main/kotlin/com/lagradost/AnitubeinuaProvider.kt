package com.lagradost

import android.util.Log
import com.lagradost.models.PlayerJson
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import org.jsoup.nodes.Element
import java.nio.charset.Charset

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

        var someInfo = document.select("div.story_c_r")[1]

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
        document.select("script").map{ script ->
            if (script.data().contains("RalodePlayer.init(")) {
                val playerScriptRawJson = script.data().substringAfterLast(".init(").substringBefore(");")
                val playerEpisodesRawJson = playerScriptRawJson.substringAfter("],").substringBeforeLast(",")
                val playerNamesArray = (playerScriptRawJson.substringBefore("],") + "]").dropLast(1).drop(1).split(",")
                val numberOfEpisodesInt = playerScriptRawJson.substringAfterLast(",").toIntOrNull()
                Log.d("load-debug", playerNamesArray[0].dropLast(1).drop(1))

                // TODO: Decode string
                Log.d("load-debug",  decode(playerNamesArray[0]))
                val playerJson = tryParseJson<List<List<PlayerJson>>>(playerEpisodesRawJson)!!
                for(item in playerJson) {
                    for (item2 in item) {
                        //Log.d("load-debug", item2.name)
                    }
                }
            }

        } //.substringAfterLast(".init(").substringBefore(");")




        var episodes: List<Episode> = emptyList()

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


        return true
    }

    fun decode(input: String): String = java.net.URLDecoder.decode(input, "utf-16")
}