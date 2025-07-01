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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.json.JSONObject
import org.jsoup.nodes.Element

class CikavaIdeyaProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://cikava-ideya.top"
    override var name = "Цікава Ідея"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Cartoon,
    )

    private var dle_login_hash = ""

    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/filmy/page/" to "Фільми",
        "$mainUrl/serialy/page/" to "Серіали",
        "$mainUrl/arthaus/page/" to "Артхаус",
        "$mainUrl/cartoon/page/" to "Мультсеріали",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document

        val home = document.select(".th-item").map {
            it.toSearchResponse()
        }

        // Need for search
        dle_login_hash =
            document
                .body()
                .selectFirst("script")!!
                .html()
                .substringAfterLast("dle_login_hash = '")
                .substringBefore("';")

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val title = this.selectFirst(".th-title")?.text()?.trim().toString()
        val href = this.selectFirst(".th-in")?.attr("href").toString()
        val posterUrl = mainUrl + this.selectFirst(".img-fit img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }

    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val document =
            app.post(
                url = mainUrl,
                data =
                mapOf(
                    "do" to "search",
                    "subaction" to "search",
                    "story" to query.replace(" ", "+")))
                .document

        return document.select(".th-item").map { it.toSearchResponse() }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        // Parse info
        val fullInfo = document.select(".flist li")
        val title = document.selectFirst(".full h1")?.text()?.trim().toString()
        val poster = mainUrl + document.selectFirst(".img-fit img")?.attr("src")
        val banner = document.select(".fx-row").attr("data-img")
        val tags = fullInfo[2].select("a").map { it.text() }
        val year = fullInfo[0].select("li").text().toIntOrNull()
        val playerUrl = document.select(".video-box iframe").attr("src")

        val tvType = if (tags.contains("Фільми") or tags.contains("Артхаус")) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst(".fdesc")?.text()?.trim()
        // val trailer = document.selectFirst("div#trailer_place iframe")?.attr("src").toString()
        val rating = document.select(".likes").text().dropLast(1).toRatingInt()
        // val actors = fullInfo[4].select("a").map { it.text() }

        val recommendations = document.select(".th-rel").map {
            it.toSearchResponse()
        }

        // Grab player json from html
        var playerJson = JSONObject()
        document.select("script").forEach {
            val scriptContent = it.html()
            // Skip if no switches
            if (!scriptContent.contains("switches = Object")) return@forEach

            val jsonStart = scriptContent.indexOf("Object(") + "Object(".length
            val jsonEnd = scriptContent.lastIndexOf(");")
            val jsonString = scriptContent.substring(jsonStart, jsonEnd)

            playerJson = JSONObject(jsonString)
            // Log.d("CakesTwix-Debug", playerJson.getJSONObject("Player1").toString())
        }

        // Return to app
        // Parse Episodes as Series
        return if (tvType == TvType.TvSeries) {
            val episodesList = mutableListOf<Episode>()

            if (playerJson.has("Player1")) {
                val player1 = playerJson.getJSONObject("Player1")
                for (seasonKey in player1.keys()) {

                    val episodes = player1.getJSONObject(seasonKey)
                    for (episodeKey in episodes.keys()) {
                        episodesList.add(
                            Episode(
                                episodes.getString(episodeKey),
                                episodeKey,
                                seasonKey.replace(" сезон","").toIntOrNull(),
                                episodeKey.replace(" серія","").toIntOrNull(),
                            )
                        )
                    }
                }
            }


            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                // addActors(actors)
                this.recommendations = recommendations
                // addTrailer(trailer)
            }
        } else { // Parse as Movie.
            newMovieLoadResponse(title, url, TvType.Movie, playerJson.getString("Player1")) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                // addActors(actors)
                this.recommendations = recommendations
                // addTrailer(trailer)
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
        val document = app.get(data).document
        val m3u8Url = document.select("script").html()
            .substringAfterLast("file:\"")
            .substringBefore("\",")
        M3u8Helper.generateM3u8(
            source = "Цікава Ідея",
            streamUrl = m3u8Url.replace("https://", "http://"),
            referer = "https://tortuga.wtf/"
        ).last().let(callback)

        val subtitleUrl = document.select("script").html()
                .substringAfterLast("subtitle:\"")
                .substringBefore("\",")

        if(subtitleUrl.isNullOrBlank()) return true
        subtitleCallback.invoke(
            SubtitleFile(
                subtitleUrl.substringAfterLast("[").substringBefore("]"),
                subtitleUrl.substringAfter("]")
            )
        )
        return true
    }
}
