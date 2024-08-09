package com.lagradost

import com.lagradost.models.PlayerJson
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.GeneralInfo
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class KlonTVProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://klon.tv"
    override var name = "KlonTV"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.TvSeries,
        TvType.Cartoon,
        TvType.Movie,
    )

    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/serialy/page/" to "Серіали",
        "$mainUrl/anime/page/" to "Аніме",
        "$mainUrl/filmy/page/" to "Зарубіжні фільми",
        "$mainUrl/multfilmy/page/" to "Мультфільми",
        "$mainUrl/multserialy/page/" to "Мультсеріали",
    )

    // Main Page
    private val animeSelector = ".short-news__slide-item"
    private val titleSelector = ".card-link__style, .text-module__main"
    private val hrefSelector = titleSelector
    private val posterSelector = ".card-poster__img, .cover-image, .owl-carousel .owl-item img"

    // Load info
    private val titleLoadSelector = ".seo-h1__position"
    private val genresSelector = ".table-info__link"
    private val yearSelector = ".table-info__link a"
    private val playerSelector = "div.film-player iframe"
    private val descriptionSelector = ".info-clamp__hid"
    private val recommendationsSelector = ".related-news__small-card"
    // private val ratingSelector = ".pmovie__subrating img"

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document

        val home = document.select(animeSelector).map {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): AnimeSearchResponse {
        val title = this.selectFirst(titleSelector)?.text()?.trim().toString()
        val href = this.selectFirst(hrefSelector)?.attr("href").toString()
        val posterUrl = mainUrl + this.selectFirst(posterSelector)?.attr("data-src")
        val status = this.select(".poster__label").text()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(isDub = true)
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

        return document.select(animeSelector).map {
            it.toSearchResponse()
        }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        // Parse info
        val titleJson = tryParseJson<GeneralInfo>(document.selectFirst("script[type=application/ld+json]")?.html())!!

        // JSON
        val title = titleJson.name
        val poster = titleJson.image
        val rating = titleJson.aggregateRating?.ratingValue.toString().toRatingInt()
        val actors = titleJson.actor.map { it.name }

        // HTML
        val tags = document.select(genresSelector).map { it.text() }
        val year = document.selectFirst(yearSelector)?.text()?.toIntOrNull()
        val playerUrl = document.select(playerSelector).attr("data-src")

        var tvType = with(tags){
            when{
                contains("Серіали") -> TvType.TvSeries
                contains("Фільми") -> TvType.Movie
                contains("Аніме") -> TvType.Anime
                contains("Мультфільми") -> TvType.Movie
                contains("Мультсеріали") -> TvType.TvSeries
                else -> TvType.TvSeries
            }
        }

        // https://klon.tv/filmy/1783-rik-ta-morti.html not movie
        if(playerUrl.contains("/serial/")){ tvType = TvType.TvSeries }
        val description = Jsoup.parse(titleJson.description).text()

        val recommendations = document.select(recommendationsSelector).map {
            it.toSearchResponse()
        }

        // Return to app
        // Parse Episodes as Series
        return if (tvType != TvType.Movie) {
            val episodes = mutableListOf<Episode>()
            val playerRawJson = app.get(playerUrl).document.select("script").html()
                .substringAfterLast("file:\'")
                .substringBefore("\',")

            tryParseJson<List<PlayerJson>>(playerRawJson)?.map { dubs -> // Dubs
                for(season in dubs.folder){                              // Seasons
                    for(episode in season.folder){                       // Episodes
                        episodes.add(
                            Episode(
                                "${season.title}, ${episode.title}, $playerUrl",
                                episode.title,
                                season.title.replace(" Сезон ","").toIntOrNull(),
                                episode.title.replace("Серія ","").toIntOrNull(),
                                episode.poster
                            )
                        )
                    }
                }
            }
            newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.recommendations = recommendations
                addEpisodes(DubStatus.Dubbed, episodes)
                addActors(actors)
            }
        } else { // Parse as Movie.
            newMovieLoadResponse(title, url, tvType, "$title, $playerUrl") {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.recommendations = recommendations
                addActors(actors)
            }
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

        tryParseJson<List<PlayerJson>>(playerRawJson)?.map { dubs ->   // Dubs
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