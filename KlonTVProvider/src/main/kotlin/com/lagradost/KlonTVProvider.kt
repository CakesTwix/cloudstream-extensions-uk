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
    override var mainUrl = "https://klon.fun"
    override var name = "KlonTV"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
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

    val fileRegex = "file\\s*:\\s*[\"']([^\",']+?)[\"']".toRegex()

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

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

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
        val titleJson = tryParseJson<GeneralInfo>(
            document.selectFirst("script[type=application/ld+json]")?.html()
        )!!

        // JSON
        val title = titleJson.name
        val poster = titleJson.image
        val rating = titleJson.aggregateRating?.ratingValue.toString()
        val actors = titleJson.actor.map { it.name }

        // HTML
        val tags = document.select(genresSelector).map { it.text() }
        val year = document.selectFirst(yearSelector)?.text()?.toIntOrNull()
        val playerUrl = document.select(playerSelector).attr("data-src")

        var tvType = with(tags) {
            when {
                contains("Серіали") -> TvType.TvSeries
                contains("Фільми") -> TvType.Movie
                contains("Аніме") -> TvType.Anime
                contains("Мультфільми") -> TvType.Movie
                contains("Мультсеріали") -> TvType.TvSeries
                else -> TvType.TvSeries
            }
        }

        // https://klon.fun/filmy/1783-rik-ta-morti.html not movie
        if (playerUrl.contains("/serial/")) {
            tvType = TvType.TvSeries
        }
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
                for (season in dubs.folder) {                              // Seasons
                    for (episode in season.folder) {                       // Episodes
                        episodes.add(
                            newEpisode("${season.title}, ${episode.title}, $playerUrl") {
                                this.name = episode.title
                                this.season = season.title.replace(" Сезон ","").toIntOrNull()
                                this.episode = episode.title.replace("Серія ","").toIntOrNull()
                                this.posterUrl = episode.poster
                                this.data = "${season.title}, ${episode.title}, $playerUrl"
                            }
                        )
                    }
                }
            }
            newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
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
                this.score = Score.from10(rating)
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
        if (dataList.size == 2) {
            // TODO: Remove this hack
            val m3u8Url = fileRegex.find(app.get(dataList[1].replace("?multivoice", "")).document.select("script[type=text/javascript]").html())?.groups?.get(1)?.value.toString()

            M3u8Helper.generateM3u8(
                source = dataList[0],
                streamUrl = m3u8Url,
                referer = "https://tortuga.wtf/"
            ).dropLast(1).forEach(callback)

            val subtitleUrl = app.get(dataList[1]).document.select("script").html()
                .substringAfterLast("subtitle: \"")
                .substringBefore("\",")

            if (subtitleUrl.isNullOrBlank()) return true
            subtitleCallback.invoke(
                newSubtitleFile(
                    subtitleUrl.substringAfterLast("[").substringBefore("]"),
                    subtitleUrl.substringAfter("]")
                )
            )
            return true
        }

        val playerRawJson = fileRegex.find(app.get(dataList[2]).document.select("script[type=text/javascript]").html())?.groups?.get(1)?.value.toString()

        tryParseJson<List<PlayerJson>>(playerRawJson)?.forEach { dub ->
            dub.folder.filter { it.title == dataList[0] }
                ?.flatMap { it.folder }
                ?.filter { it.title == dataList[1] }
                ?.map {
                    M3u8Helper.generateM3u8(
                        source = dub.title,
                        streamUrl = it.file,
                        referer = "https://tortuga.wtf/"
                    ).dropLast(1).forEach(callback)

                    if (!it.subtitle.isNullOrBlank()) {
                        subtitleCallback.invoke(
                            SubtitleFile(
                                it.subtitle.substringAfterLast("[").substringBefore("]"),
                                it.subtitle.substringAfter("]")
                            )
                        )
                    }
                }
        }
        return true
    }

}