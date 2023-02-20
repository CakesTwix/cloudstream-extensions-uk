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
import java.util.*

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

        return newMovieSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
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

        return document.select("article.story").map {
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
        // Players, Episodes, Number of episodes
        var episodes: List<Episode> = emptyList()
        val id = url.split("/").last().split("-").first()
        val responseGet = app.get("$mainUrl/engine/ajax/playlists.php?news_id=$id&xfield=playlist&time=${Date().time}").parsedSafe<Responses>()!!
        if (responseGet?.success == true) { // First type players
            episodes =
                app.get("$mainUrl/engine/ajax/playlists.php?news_id=$id&xfield=playlist&time=${Date().time}")
                    .parsedSafe<Responses>()?.response.let {
                        Jsoup.parse(it.toString()).select("div.playlists-videos li")
                            .mapIndexedNotNull() { index, eps ->
                                val href =
                                    "$mainUrl/engine/ajax/playlists.php?news_id=$id&xfield=playlist&time=${Date().time}"
                                val name = eps.text().trim() // Серія 1
                                if (href.isNotEmpty()) {
                                    Episode(
                                        "$href, $index", // link, Серія 1
                                        name,
                                    )
                                } else {
                                    null
                                }
                            }
                    }
        } else {
            document.select("script").map{ script ->
                if (script.data().contains("RalodePlayer.init(")) {
                    val playerScriptRawJson = script.data().substringAfterLast(".init(").substringBefore(");")
                    val playerEpisodesRawJson = playerScriptRawJson.substringAfter("],").substringBeforeLast(",")
                    val playerNamesArray = (playerScriptRawJson.substringBefore("],") + "]").dropLast(1).drop(1).replace("\",\"", ",,,").split(",,,")
                    val numberOfEpisodesInt = playerScriptRawJson.substringAfterLast(",").toIntOrNull()

                    val playerJson = tryParseJson<List<List<PlayerJson>>>(playerEpisodesRawJson)!!
                    for(item in playerJson) {
                        item.forEachIndexed { index, item2 ->
                            if(!item2.name.contains("ПЛЕЙЛИСТ")) // UFDub player
                            {
                                episodes = episodes.plus(
                                    Episode(
                                        "$index, $url",
                                        item2.name,
                                        episode = item2.name.replace("Серія ","").toIntOrNull(),
                                    )
                                )
                            }
                        }
                    }
                }

            }
        }

        return newTvSeriesLoadResponse(title, url, tvType, episodes.distinctBy{ it.name }) {
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
        data: String, // (First) link, index | (Two) index, url title
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split(", ")

        Log.d("load-debug",  dataList.toString())
        if(dataList[0].contains("https://")){ // Its First type player
            Log.d("load-debug",  "First Player pack")

            // Get episodes list (as json)
            val responseGet = app.get(dataList[0]).parsedSafe<Responses>() // ajax link
            responseGet?.response?.let {

                // List Players
                val playersTab = Jsoup.parse(it).select("div.playlists-items")

                // Parse all episodes by name
                var index = 0
                var player_tab_id = ""
                Jsoup.parse(it).select("div.playlists-videos li")
                    .mapNotNull { eps ->
                        // 0 - idk, 1 - dub, 2 - player
                        // dataList[1] - index
                        Log.d("load-debug", index.toString())
                        // 0_1_2
                        if(player_tab_id != eps.attr("data-id")){
                            index = -1
                            player_tab_id = eps.attr("data-id")
                        }

                        if(dataList[1].toInt() == index){
                            var href = eps.attr("data-file")  // m3u url
                            // Can be without https:
                            if (!href.contains("https://")) {
                                href = "https:$href"
                            }

                            val dub_name = playersTab[0].select(" li[data-id=${ player_tab_id.dropLast(2) }]").text() // G&M
                            val player_name = playersTab[1].select(" li[data-id=$player_tab_id]").text()

                            if (href.contains("https://ashdi.vip/vod")) {
                                // Add as source
                                M3u8Helper.generateM3u8(
                                    source = "$player_name ($dub_name)",
                                    streamUrl = AshdiExtractor().ParseM3U8(href),
                                    referer = "https://qeruya.cyou"
                                ).forEach(callback)
                            }
                        }
                        index += 1
                    }
            }
        } else {
            val document = app.get(dataList[1]).document
            document.select("script").map { script ->
                if (script.data().contains("RalodePlayer.init(")) {
                    val playerScriptRawJson = script.data().substringAfterLast(".init(").substringBefore(");")
                    val playerEpisodesRawJson = playerScriptRawJson.substringAfter("],").substringBeforeLast(",")
                    val playerNamesArray = (playerScriptRawJson.substringBefore("],") + "]").dropLast(1).drop(1).replace("\",\"", ",,,").split(",,,")

                    val playerJson = tryParseJson<List<List<PlayerJson>>>(playerEpisodesRawJson)!!
                    playerJson.forEachIndexed { index, dub ->
                        if(dub[dataList[0].toInt()].code.contains("https://ashdi.vip")){
                            M3u8Helper.generateM3u8(
                                source = decode(playerNamesArray[index]),
                                streamUrl = AshdiExtractor().ParseM3U8(Jsoup.parse(dub[dataList[0].toInt()].code).select("iframe").attr("src")),
                                referer = "https://qeruya.cyou"
                            ).forEach(callback)
                        }

                    }
                }
            }
        }
        return true
    }

    fun decode(input: String): String{
        // Decoded string, thanks to Secozzi
        val hexRegex = Regex("\\\\u([0-9a-fA-F]{4})")
        return hexRegex.replace(input) { matchResult ->
            Integer.parseInt(matchResult.groupValues[1], 16).toChar().toString()
        }
    }

    data class Responses(
        val success: Boolean?,
        val response: String?,
        val message: String?
    )
}