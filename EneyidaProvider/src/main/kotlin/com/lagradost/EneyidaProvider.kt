package com.lagradost

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.*

class EneyidaProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://eneyida.tv"
    override var name = "Eneyida"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/films/page/" to "Фільми",
        "$mainUrl/series/page/" to "Серіали",
        "$mainUrl/anime/page/" to "Аніме",
        "$mainUrl/cartoon/page/" to "Мультфільми",
        "$mainUrl/cartoon-series/page/" to "Мультсеріали",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        var document = app.get(request.data + page).document

        val home = document.select("article.short").map {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val title = this.selectFirst("a.short_title")?.text()?.trim().toString()
        val href = this.selectFirst("a.short_title")?.attr("href").toString()
        val posterUrl = mainUrl + this.selectFirst("a.short_img img")?.attr("data-src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
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
        return document.select("div.movie-item.short-item").map {
            it.toSearchResponse()
        }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Parse info
        val full_info = document.select(".full_info li")
        val title = document.selectFirst("div.full_header-title h1")?.text()?.trim().toString()
        val poster = mainUrl + document.selectFirst(".full_content-poster img")?.attr("src")
        val tags = full_info[1].select("a").map { it.text() }
        val year = full_info[0].select("a").text().toIntOrNull()
        val playerUrl = document.select(".tabs_b.visible iframe").attr("src")

        val tvType = if (tags.contains("фільм") or playerUrl.contains("/vod/")) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst(".full_content-desc p")?.text()?.trim()
        val trailer = document.selectFirst("div#trailer_place iframe")?.attr("src").toString()
        val rating = document.selectFirst(".r_kp span, .r_imdb span")?.text().toRatingInt()
        val actors = full_info[4].select("a").map { it.text() }

        val recommendations = document.select(".short.related_item").map {
            it.toSearchResponse()
        }

        // Return to app
        // Parse Episodes as Series
        return if (tvType == TvType.TvSeries) {
            val id = url.split("/").last().split("-").first()
            val episodes =
                app.get("$mainUrl/engine/ajax/playlists.php?news_id=$id&xfield=playlist&time=${Date().time}")
                    .parsedSafe<Responses>()?.response.let {
                        Jsoup.parse(it.toString()).select("div.playlists-videos li").mapNotNull { eps ->
                            val href = "$mainUrl/engine/ajax/playlists.php?news_id=$id&xfield=playlist&time=${Date().time}"
                            val name = eps.text().trim() // Серія 1
                            if (href.isNotEmpty()) {
                                Episode(
                                    "$href,$name", // link, Серія 1
                                    name,
                                )
                            } else {
                                null
                            }
                        }
                    }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy{ it.name }) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else { // Parse as Movie.
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        }
    }

    // It works when I click to view the series
    override suspend fun loadLinks(
        data: String, // link, episode name
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split(",")
        // TODO: OPTIMIZE code!!! Remove this shitty code as soon as possible!!!!!!
        if(dataList.size == 1){
            val id = data.split("/").last().split("-").first()
            val responseGet = app.get("$mainUrl/engine/ajax/playlists.php?news_id=$id&xfield=playlist&time=${Date().time}").parsedSafe<Responses>()
            if (responseGet?.success == true) { // Its serial
                responseGet?.response?.let {
                    Jsoup.parse(it).select("div.playlists-videos li")
                        .mapNotNull { eps ->
                            var href = eps.attr("data-file")  // ashdi
                            // Can be without https:
                            if (!href.contains("https://")) {
                                href = "https:$href"
                            }
                            val dub = eps.attr("data-voice")  // FanWoxUA

                            // Get m3u from player script
                            app.get(href, referer = "$mainUrl/").document.select("script")
                                .map { script ->
                                    if (script.data().contains("var player = new Playerjs({")) {
                                        val m3uLink = script.data().substringAfterLast("file:\"")
                                            .substringBefore("\",")

                                        // Add as source
                                        M3u8Helper.generateM3u8(
                                            source = dub,
                                            streamUrl = m3uLink,
                                            referer = "https://ashdi.vip/"
                                        ).forEach(callback)
                                    }
                                }
                        }
                }
            } else {
                // Its maybe film
                val document = app.get(data).document
                val iframeUrl = document.selectFirst("iframe#pre")?.attr("src")
                // Get m3u from player script
                if (iframeUrl != null) {
                    app.get(iframeUrl, referer = "$mainUrl/").document.select("script").map { script ->
                        if (script.data().contains("var player = new Playerjs({")) {
                            val m3uLink = script.data().substringAfterLast("file:\"").substringBefore("\",")

                            // Add as source
                            M3u8Helper.generateM3u8(
                                source = document.selectFirst("h1 span.solototle")?.text()?.trim().toString(),
                                streamUrl = m3uLink,
                                referer = "https://ashdi.vip/"
                            ).forEach(callback)
                        }
                    }
                }
            }
            return true
        }

        val responseGet = app.get(dataList[0]).parsedSafe<Responses>() // ajax link
        if (responseGet?.success == true){ // Its serial
            responseGet?.response?.let {
                Jsoup.parse(it).select("div.playlists-videos li:contains(${dataList[1]})").mapNotNull { eps ->
                    var href = eps.attr("data-file")  // ashdi
                    // Can be without https:
                    if (! href.contains("https://")) {
                        href = "https:$href"
                    }
                    val dub = eps.attr("data-voice")  // FanWoxUA

                    // Get m3u from player script
                    app.get(href, referer = "$mainUrl/").document.select("script").map { script ->
                        if (script.data().contains("var player = new Playerjs({")) {
                            val m3uLink = script.data().substringAfterLast("file:\"").substringBefore("\",")

                            // Add as source
                            M3u8Helper.generateM3u8(
                                source = dub,
                                streamUrl = m3uLink,
                                referer = "https://ashdi.vip/"
                            ).forEach(callback)
                        }
                    }
                }
            }
        } else {
            // Its maybe film
            val document = app.get(data).document
            val iframeUrl = document.selectFirst("iframe#pre")?.attr("src")
            // Get m3u from player script
            if (iframeUrl != null) {
                app.get(iframeUrl, referer = "$mainUrl/").document.select("script").map { script ->
                    if (script.data().contains("var player = new Playerjs({")) {
                        val m3uLink = script.data().substringAfterLast("file:\"").substringBefore("\",")

                        // Add as source
                        M3u8Helper.generateM3u8(
                            source = document.selectFirst("h1 span.solototle")?.text()?.trim().toString(),
                            streamUrl = m3uLink,
                            referer = "https://ashdi.vip/"
                        ).forEach(callback)
                    }
                }
            }
        }


        return true
    }

    data class Responses(
        val success: Boolean?,
        val response: String,
    )

}