package com.lagradost

import com.lagradost.models.PlayerJson
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Element

class EneyidaProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://eneyida.tv"
    override var name = "Eneyida"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
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
        val document = app.get(request.data + page).document

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

        return document.select("article.short").map {
            it.toSearchResponse()
        }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        // Parse info
        val fullInfo = document.select(".full_info li")
        val title = document.selectFirst("div.full_header-title h1")?.text()?.trim().toString()
        val poster = mainUrl + document.selectFirst(".full_content-poster img")?.attr("src")
        val banner = document.select(".full_header__bg-img").attr("style").substringAfterLast("url(").substringBefore(");")
        val tags = fullInfo[1].select("a").map { it.text() }
        val year = fullInfo[0].select("a").text().toIntOrNull()
        val playerUrl = document.select(".tabs_b.visible iframe").attr("src")

        val tvType = if (tags.contains("фільм") or tags.contains("мультьфільм") or playerUrl.contains("/vod/")) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst(".full_content-desc p")?.text()?.trim()
        val trailer = document.selectFirst("div#trailer_place iframe")?.attr("src").toString()
        val rating = document.selectFirst(".r_kp span, .r_imdb span")?.text().toRatingInt()
        val actors = fullInfo[4].select("a").map { it.text() }

        val recommendations = document.select(".short.related_item").map {
            it.toSearchResponse()
        }

        // Return to app
        // Parse Episodes as Series
        return if (tvType == TvType.TvSeries) {
            val episodes = mutableListOf<Episode>()
            val playerRawJson = app.get(playerUrl).document.select("script").html()
                .substringAfterLast("file: \'")
                .substringBefore("\',")

            tryParseJson<List<PlayerJson>>(playerRawJson)?.map { season -> // Dubs
                for (episode in season.folder) {                                     // Seasons
                    for (dubs in episode.folder) {                              // Episodes
                        episodes.add(
                            Episode(
                                "${season.title}, ${episode.title}, $playerUrl",
                                episode.title,
                                season.title.replace(" сезон","").toIntOrNull(),
                                episode.title.replace(" серія","").toIntOrNull(),
                                dubs.poster
                            )
                        )
                    }
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = "$mainUrl$banner"
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else { // Parse as Movie.
            newMovieLoadResponse(title, url, TvType.Movie, "$title, $playerUrl") {
                this.posterUrl = poster
                this.backgroundPosterUrl = "$mainUrl$banner"
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
        data: String, // (Serisl) [Season, Episode, Player Url] | (Film) [Title, Player Url]
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split(", ")

        // Its film, parse one m3u8
        if(dataList.size == 2){
            val m3u8Url = app.get(dataList[1]).document.select("script").html()
                .substringAfterLast("file: \"")
                .substringBefore("\",")
            M3u8Helper.generateM3u8(
                source = dataList[0],
                streamUrl = m3u8Url.replace("https://", "http://"),
                referer = "https://tortuga.wtf/"
            ).last().let(callback)

            val subtitleUrl = app.get(dataList[1]).document.select("script").html()
                    .substringAfterLast("subtitle: \"")
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

        val playerRawJson = app.get(dataList[2]).document.select("script").html()
            .substringAfterLast("file: \'")
            .substringBefore("\',")

        tryParseJson<List<PlayerJson>>(playerRawJson)
            ?.filter { it.title == dataList[0] } // Фільтруємо потрібний сезон
            ?.flatMap { it.folder }              // Беремо список епізодів
            ?.filter { it.title == dataList[1] } // Фільтруємо потрібний епізод
            ?.flatMap { it.folder }              // Беремо список дубляжів
            ?.forEach { dubs ->                  // Обробляємо кожен дубляж
                M3u8Helper.generateM3u8(
                    source = dubs.title,
                    streamUrl = dubs.file,
                    referer = "https://tortuga.wtf/"
                ).last().let(callback)

                if (!dubs.subtitle.isNullOrBlank()) {
                    subtitleCallback.invoke(
                        SubtitleFile(
                            dubs.subtitle.substringAfterLast("[").substringBefore("]"),
                            dubs.subtitle.substringAfter("]")
                        )
                    )
                }
            }
        return true
    }

}