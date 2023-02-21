package com.lagradost

import android.annotation.SuppressLint
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.GeneralInfo
import org.jsoup.nodes.Element

class UASerialProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://uaserial.tv"
    override var name = "UASerial"
    override val hasMainPage = true
    override var lang = "uk"
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
        var document = app.get(request.data.format(page)).document

        val home = document.select(".row .col").map {
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
        // Log.d("load-debug", document.select("script[type=application/ld+json]").html())
        val titleJson =
            tryParseJson<GeneralInfo>(document.select("script[type=application/ld+json]").html())!!

        // Parse info
        val title = titleJson.partOfTVSeries.name
        val poster = mainUrl + document.selectFirst("img.cover")?.attr("src")
        val tags = document.select("div.genre div a").map { it.text() }
        val year = document.select("div.release div a").text().toIntOrNull()

        val tvType = TvType.TvSeries
        val description = document.selectFirst(".text")?.text()?.trim()
        val rating = document.select("div.rating__item--imdb div.number").text().toRatingInt()

        // val actors = full_info[4].select("a").map { it.text() }

        var episodes: List<Episode> = emptyList()
        titleJson.partOfTVSeries.containsSeason.map { season ->
            val documentSeason = app.get(season.url).document
            season.episode.map { episode ->
                var episodeName = documentSeason.select("div[data-episode-id=${episode.episodeNumber + 1}] div.name").text().replaceFirstChar { it.uppercase() }
                if (episodeName.isBlank()) { episodeName = episode.name.replaceFirstChar { it.uppercase() } }
                episodes = episodes.plus(
                Episode(
                    "${season.url}, ${episode.episodeNumber}",
                    episodeName,
                    season.seasonNumber,
                    episode.episodeNumber
                )
                )
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
        data: String, // Url with season, episode number
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split(", ")
        val document = app.get(mainUrl + app.get(dataList[0]).document.select("option[data-series-number=${dataList[1]}]").attr("value")).document
        document.select(".player .voices__wrap").map{ player ->
            // Log.d("load-debug", player.attr("data-player-id")) // Player name
            player.select("select.voices__select option").map{ dub ->
                // Log.d("load-debug", dub.text()) // Name
                // Log.d("load-debug", dub.attr("value"))// Url

                val m3u8Url = app.get(dub.attr("value")).document.select("script").html()
                    .substringAfterLast("file:\"")
                    .substringBefore("\",")

                M3u8Helper.generateM3u8(
                    source = "${dub.text()} (${player.attr("data-player-id").replaceFirstChar { it.uppercase() }})",
                    streamUrl = m3u8Url,
                    referer = "https://tortuga.wtf/"
                ).forEach(callback)
            }
        }
        // selecto__dropdown-item
        return true
    }

}