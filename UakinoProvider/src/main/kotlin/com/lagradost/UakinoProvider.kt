package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import java.util.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class UakinoProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://uakino.best"
    override var name = "Uakino"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // Sections
    override val mainPage =
        mainPageOf(
            "$mainUrl/filmy/page/" to "Фільми",
            "$mainUrl/seriesss/page/" to "Серіали",
            "$mainUrl/seriesss/doramy/page/" to "Дорами",
            "$mainUrl/animeukr/page/" to "Аніме",
            "$mainUrl/cartoon/page/" to "Мультфільми",
            "$mainUrl/cartoon/cartoonseries/page/" to "Мультсеріали",
        )

    val blackUrls = "(/news/)|(/franchise/)"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home =
            document
                .select("div.owl-item, div.movie-item")
                .filterNot { el ->
                    el.select("a.movie-title, a.full-movie").attr("href").contains(Regex(blackUrls))
                }
                .map {
                    // Log.d("CakesTwix-Debug", it.select("a.movie-title, a.full-movie").attr("href"))
                    it.toSearchResponse()
                }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val title =
            this.selectFirst("a.movie-title, div.full-movie-title")?.text()?.trim().toString()
        val href = this.selectFirst("a.movie-title, a.full-movie")?.attr("href").toString()
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    private suspend fun Element.getSeasonInfo(): SearchResponse {
        // Log.d("CakesTwix-Debug", "getSeasonInfo: ${this.attr("href")}")
        val document = app.get(this.attr("href")).document
        val title = document.selectFirst("h1 span.solototle")?.text()?.trim().toString()
        val poster = mainUrl + document.selectFirst("div.film-poster img")?.attr("src").toString()

        return newMovieSearchResponse(title, this.attr("href"), TvType.Movie) {
            this.posterUrl = poster
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
                    "story" to query.replace(" ", "+")
                )
            )
                .document

        return document
            .select("div.movie-item.short-item")
            .filterNot { el ->
                el.select("a.movie-title, a.full-movie").attr("href").contains(Regex(blackUrls))
            }
            .map { it.toSearchResponse() }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Parse info
        val title = document.selectFirst("h1 span.solototle")?.text()?.trim().toString()
        val engTitle = document.selectFirst("h1 span.solototle")?.text()?.trim().toString()
        val poster = fixUrl(document.selectFirst("div.film-poster img")?.attr("src").toString())

        var tags = emptyList<String>()
        var year = 2023
        var actors = emptyList<String>()
        var rating = "0".toRatingInt()

        document.select(".fi-item-s, .fi-item").forEach { metadata ->
            with(metadata.select(".fi-label").text()) {
                when {
                    contains("Рік виходу:") -> year = metadata.select(".fi-desc").text().toInt()
                    contains("Жанр:") -> tags = metadata.select(".fi-desc").text().split(" , ")
                    contains("Актори:") -> actors = metadata.select(".fi-desc").text().split(", ")
                    contains("") -> {
                        if (!metadata.select(".fi-label").select("img").isEmpty()){
                            rating = metadata.select(".fi-desc").text().substringBefore("/").toRatingInt()
                        }
                    }
                }
                // Log.d("CakesTwix-Debug", metadata.select(".fi-desc").text().substringBefore("/"))
            }
        }

        // reversed need for check "Мультсеріали"
        // tags: Мультфільми , Мультсеріали
        // It's Cartoon, not Movie
        var tvType =
            with(tags.reversed()) {
                when {
                    contains("Повнометражне аніме") -> TvType.AnimeMovie
                    contains("Мультсеріали") -> TvType.Cartoon
                    contains("Мультфільми") -> TvType.Movie
                    contains("Багатосерійне аніме") -> TvType.Anime
                    contains("Дорами") -> TvType.AsianDrama
                    else -> TvType.Others
                }
            }
        // Log.d("CakesTwix-Debug", tvType.toString())
        if (tvType == TvType.Others) {
            tvType =
                if (url.contains(Regex("(/anime-series)|(/seriesss)|(/cartoonseries)")))
                    TvType.TvSeries
                else TvType.Movie
        }

        val description = document.selectFirst("div[itemprop=description]")?.text()?.trim()
        val trailer = document.selectFirst("iframe#pre")?.attr("data-src")

        // Add seasons to recommendations
        val recommendations =
            document.select(".seasons li a").map { it.getSeasonInfo() }.toMutableList()

        // Other recommendations
        recommendations += document.select(".related-item").map { it.toSearchResponse() }

        // Return to app
        // Parse Episodes as Series
        return if (tvType != TvType.Movie && tvType != TvType.AnimeMovie) {
            val id = url.split("/").last().split("-").first()
            val episodes =
                app.get(
                    "$mainUrl/engine/ajax/playlists.php?news_id=$id&xfield=playlist&time=${Date().time}",
                        headers = mapOf(
                            "Referer" to mainUrl,
                            "X-Requested-With" to "XMLHttpRequest",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; rv:126.0) Gecko/20100101 Firefox/126.0",
                )
                )
                    .parsedSafe<Responses>()
                    ?.response
                    .let {
                        Jsoup.parse(it.toString()).select("div.playlists-videos li").mapNotNull {
                                eps ->
                            val href =
                                "$mainUrl/engine/ajax/playlists.php?news_id=$id&xfield=playlist&time=${Date().time}"
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
            newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = poster
                this.engName = engTitle
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
                addEpisodes(DubStatus.None, episodes.distinctBy { it.name })
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else { // Parse as Movie.
            newMovieLoadResponse(title, url, tvType, url) {
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
        if (dataList.size == 1) {
            val id = data.split("/").last().split("-").first()
            val responseGet =
                app.get(
                    "$mainUrl/engine/ajax/playlists.php?news_id=$id&xfield=playlist&time=${Date().time}",
                        headers = mapOf(
                                "Referer" to mainUrl,
                                "X-Requested-With" to "XMLHttpRequest",
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; rv:126.0) Gecko/20100101 Firefox/126.0",
                        )
                )
                    .parsedSafe<Responses>()
            if (responseGet?.success == true) { // Its serial
                responseGet.response?.let {
                    Jsoup.parse(it).select("div.playlists-videos li").mapNotNull { eps ->
                        var href = eps.attr("data-file") // ashdi
                        // Can be without https:
                        if (!href.contains("https://")) {
                            href = "https:$href"
                        }
                        val dub = eps.attr("data-voice") // FanWoxUA

                        // Get m3u from player script
                        app.get(href, referer = "$mainUrl/").document.select("script").map { script
                            ->
                            if (script.data().contains("var player = new Playerjs({")) {
                                val m3uLink =
                                    script
                                        .data()
                                        .substringAfterLast("file:\"")
                                        .substringBefore("\",")

                                // Add as source
                                M3u8Helper.generateM3u8(
                                    source = dub,
                                    streamUrl = m3uLink,
                                    referer = "https://ashdi.vip/"
                                )
                                    .last().let(callback)
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
                    app.get(iframeUrl, referer = "$mainUrl/").document.select("script").map { script
                        ->
                        if (script.data().contains("var player = new Playerjs({")) {
                            val m3uLink =
                                script.data().substringAfterLast("file:\"").substringBefore("\",")

                            // Add as source
                            M3u8Helper.generateM3u8(
                                source =
                                document
                                    .selectFirst("h1 span.solototle")
                                    ?.text()
                                    ?.trim()
                                    .toString(),
                                streamUrl = m3uLink,
                                referer = "https://ashdi.vip/"
                            )
                                .last().let(callback)
                        }
                    }
                }
            }
            return true
        }

        val responseGet = app.get(dataList[0],
                headers = mapOf(
                        "Referer" to mainUrl,
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; rv:126.0) Gecko/20100101 Firefox/126.0",
                )).parsedSafe<Responses>() // ajax link
        if (responseGet?.success == true) { // Its serial
            responseGet?.response?.let {
                Jsoup.parse(it)
                    .select("div.playlists-videos li:contains(${dataList[1]})")
                    .mapNotNull { eps ->
                        if (eps.text() != dataList[1]) return@mapNotNull
                        var href = eps.attr("data-file") // ashdi
                        // Can be without https:
                        if (!href.contains("https://")) {
                            href = "https:$href"
                        }
                        val dub = eps.attr("data-voice") // FanWoxUA

                        // Get m3u from player script
                        app.get(href, referer = "$mainUrl/").document.select("script").map { script
                            ->
                            if (script.data().contains("var player = new Playerjs({")) {
                                val m3uLink =
                                    script
                                        .data()
                                        .substringAfterLast("file:\"")
                                        .substringBefore("\",")

                                // Add as source
                                M3u8Helper.generateM3u8(
                                    source = dub,
                                    streamUrl = m3uLink,
                                    referer = "https://ashdi.vip/"
                                )
                                    .last().let(callback)
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
                        val m3uLink =
                            script.data().substringAfterLast("file:\"").substringBefore("\",")

                        // Add as source
                        M3u8Helper.generateM3u8(
                            source =
                            document
                                .selectFirst("h1 span.solototle")
                                ?.text()
                                ?.trim()
                                .toString(),
                            streamUrl = m3uLink,
                            referer = "https://ashdi.vip/"
                        )
                            .last().let(callback)
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
