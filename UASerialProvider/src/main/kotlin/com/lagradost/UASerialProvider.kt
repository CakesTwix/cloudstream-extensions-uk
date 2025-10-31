package com.lagradost

import com.google.gson.Gson
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.GeneralInfo
import com.lagradost.models.GeneralInfoMovie
import com.lagradost.models.SearchModel
import org.jsoup.nodes.Element

open class UASerialProvider(url: String, name: String) : MainAPI() {

    // Basic Info
    override var mainUrl = url
    override var name = name
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
    )

    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/%d?priority=popular" to "⭐ За популярністю",
        "$mainUrl/%d?priority=views" to "\uD83D\uDC40 За переглядами",
        "$mainUrl/%d?priority=rating" to "\uD83C\uDFC6 За рейтингом",
        "$mainUrl/%d?priority=date" to "\uD83D\uDD25 За новизною",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data.format(page)).document

        val home = document.select("div[id=filters-grid-content]").select("div.row .col").map {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val title = this.selectFirst("div.name")?.text()?.trim().toString()
        val href = this.selectFirst("a")?.attr("href").toString()
        val posterUrl = mainUrl + this.selectFirst("img.cover")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }

    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResult = app.get(
            url = "$mainUrl/search-ajax?query=$query",
        ).text

        val searchJson = Gson().fromJson(searchResult, SearchModel::class.java)

        return searchJson.movies.map {
           newMovieSearchResponse(it.name, it.link, TvType.Movie) {
                this.posterUrl = "$mainUrl${it.poster}"
            }
        }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        // Log.d("CakesTwix-Debug", document.select("script[type=application/ld+json]").html())
        val titleJson = Gson().fromJson(document.select("script[type*=json]").html(), GeneralInfo::class.java)

        if(titleJson.type == "Movie"){
            val titleJsonMovie = Gson().fromJson(document.select("script[type*=json]").html(), GeneralInfoMovie::class.java)
            // Parse info for Serials
            val title = titleJsonMovie.name
            val poster = mainUrl + document.selectFirst("img.cover")?.attr("src")
            val tags = document.select("div.genre div a").map { it.text() }
            val year = document.select("div.release div a").text().toIntOrNull()

            val tvType = TvType.Movie
            val description = document.selectFirst(".text")?.text()?.trim()
            val rating = document.select("div.rating__item--imdb div.number").text()
            val actors = titleJsonMovie.actor.map { it.name }

            return newMovieLoadResponse(title, url, tvType, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
            }
        } else {
            // Parse info for Serials
            val title = titleJson.partOfTVSeries.name
            val poster = mainUrl + document.selectFirst("img.cover")?.attr("src")
            val tags = document.select("div.genre div a").map { it.text() }
            val year = document.select("div.release div a").text().toIntOrNull()

            val tvType = TvType.TvSeries
            val description = document.selectFirst(".text")?.text()?.trim()
            val rating = document.select("div.rating__item--imdb div.number").text()

            val actors = titleJson.partOfTVSeries.actor.map { it.name }

            val episodes = mutableListOf<Episode>()
            titleJson.partOfTVSeries.containsSeason.map { season ->
                val documentSeason = app.get(season.url).document
                season.episode.map { episode ->
                    var episodeName = documentSeason.select("div[data-episode-id=${episode.episodeNumber}] div.name").text().replaceFirstChar { it.uppercase() }
                    if (episodeName.isBlank()) { episodeName = episode.name.replaceFirstChar { it.uppercase() } }
                    episodes.add(
                        newEpisode("${season.url}, ${episode.episodeNumber}") {
                            this.name = episodeName
                            this.season = season.seasonNumber
                            this.episode = episode.episodeNumber.split("-")[0].toIntOrNull()
                            this.data = "${season.url}, ${episode.episodeNumber}"
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
            }
        }
    }


    // It works when I click to view the series
    override suspend fun loadLinks(
        data: String, // Url with season, episode number
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split(", ")
        // Its film, parse one m3u8
        if(dataList.size == 1){
            val movieHTML = app.get(data).document.select(".player .player__background .video-holder .video iframe").attr("src")
            // Log.d("CakesTwix-Debug", movieHTML)

            // https://uaserial.tv/embed/happiness-for-beginners/season-1/episode-1
            val onlyPlayerHTML = app.get(mainUrl + movieHTML).document.select(".player .voices__wrap option").attr("value")
            // Log.d("CakesTwix-Debug", onlyPlayerHTML)

            val m3u8Url = app.get(onlyPlayerHTML).document.select("script").html()
                .substringAfterLast("file:\"")
                .substringBefore("\",")

            M3u8Helper.generateM3u8(
                source = "Movie",
                streamUrl = m3u8Url,
                referer = "https://tortuga.wtf/"
            ).last().let(callback)

            return true
        }

        val document = app.get(mainUrl + app.get(dataList[0]).document.select("option[data-series-number=${dataList[1]}]").attr("value")).document
        document.select(".voices__wrap").map{ player ->
            // Log.d("load-debug", player.attr("data-player-id")) // Player name
            player.select("select.voices__select option").map{ dub ->
                // Log.d("load-debug", dub.text()) // Name
                // Log.d("load-debug", dub.attr("value"))// Url

                var m3u8Url = app.get(dub.attr("value")).document.select("script").html()
                    .substringAfterLast("file:\"")
                    .substringBefore("\",")

                if (player.attr("data-player-id") == "spilberg"){
                    m3u8Url = app.get(dub.attr("value")).document.select("script").html()
                        .substringAfterLast(" manifest: '")
                        .substringBefore("',")
                }

                if (!m3u8Url.startsWith("http")) return@map
                M3u8Helper.generateM3u8(
                    source = "${dub.text()} (${player.attr("data-player-id").replaceFirstChar { it.uppercase() }})",
                    streamUrl = m3u8Url,
                    referer = "https://tortuga.wtf/"
                ).last().let(callback)
            }
        }
        // selecto__dropdown-item
        return true
    }
}
