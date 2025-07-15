package com.lagradost

import com.lagradost.models.PlayerJson
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Element

class AnimeUAProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://animeua.club"
    override var name = "AnimeUA"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Нове аніме",
        "$mainUrl/film/page/" to "Повнометражки",
        "$mainUrl/anime/page/" to "Аніме серіали",
        "$mainUrl/ona/page/" to "ONA",
        "$mainUrl/ova/page/" to "OVA",
    )

    // Main Page
    private val animeSelector = "a.poster"
    private val titleSelector = "h3.poster__title"
    private val hrefSelector = ".fd-column"
    private val posterSelector = ".img-fit-cover img"

    // Load info
    private val titleLoadSelector = ".page__subcol-main h1"
    private val genresSelector = ".pmovie__genres a"
    private val yearSelector = ".pmovie__year"
    private val playerSelector = ".video-responsive > iframe"
    private val descriptionSelector = ".full-text"
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

        // TODO: Use it
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

        val title = document.selectFirst(titleLoadSelector)?.text()?.trim().toString()
        val engTitle = document.select(".pmovie__original-title").text()
        val poster = mainUrl + document.selectFirst("div.page__subcol-side $posterSelector")?.attr("data-src")
        val tags = document.select(genresSelector).map { it.text() }
        val year = document.select(yearSelector).text().substringAfter(": ").substringBefore("-").toIntOrNull()
        val playerUrl = document.select(playerSelector).attr("data-src")

        val tvType = with(tags){
            when{
                playerUrl.contains("/serial/") -> TvType.Anime
                contains("Повнометражка") -> TvType.AnimeMovie
                contains("OVA") -> TvType.OVA
                contains("ONA") -> TvType.Anime
                else -> TvType.Anime
            }
        }
        val description = document.selectFirst(descriptionSelector)?.text()?.trim()
        // val rating = document.select(ratingSelector).next().text().toRatingInt()

        val recommendations = document.select(animeSelector).map {
            it.toSearchResponse()
        }

        val (malId, anilistId, image, cover) = Tracker().getTracker(engTitle, "TV", year)
        // Log.d("load-debug", engTitle)
        // Log.d("load-debug", anilistId!!)
        // Return to app
        // Parse Episodes as Series
        return if (tvType == TvType.Anime || tvType == TvType.OVA) {
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
                this.engName = engTitle
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                addEpisodes(DubStatus.Dubbed, episodes)
                addMalId(malId)
                addAniListId(anilistId?.toIntOrNull())
            }
        } else { // Parse as Movie.
            newMovieLoadResponse(title, url, tvType, "$title, $playerUrl") {
                this.posterUrl = poster
                this.name = engTitle
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
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
            ).last().let(callback)

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
                            ).last().let(callback)
                        }
                    }
                }
            }
        }
        return true
    }

}