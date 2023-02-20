package com.lagradost

import android.net.Uri
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class UFDubProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://ufdub.com"
    override var name = "UFDub"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.Anime,
        TvType.AsianDrama,
        TvType.Movie,
        TvType.Cartoon,
        TvType.TvSeries
    )

    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/anime/page/" to "Аніме",
        "$mainUrl/serial/page/" to "Серіали",
        "$mainUrl/film/page/" to "Фільми",
        "$mainUrl/cartoon/page/" to "Мультфільми",
        "$mainUrl/cartoon-fiml/page/" to "Мультсеріали",
        "$mainUrl/dorama/page/" to "Дорами",

    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        document.select(".section").remove()

        val home = document.select(".short").map {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val title = this.select(".short-t").text()
        val href = this.select(".short-t").attr("href").toString()
        val posterUrl = mainUrl + this.select(".img-box img").attr("src")

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

        val someInfo = document.select("div.full-desc")

        // Parse info
        val title = document.select("h1.top-title").text()
        val poster = mainUrl + document.select("div.f-poster img").attr("src")
        var tags = emptyList<String>()
        val year = someInfo.select("strong:contains(Рік випуску аніме:)").next().html().toIntOrNull()

        // TODO: Check type by url
        val tvType = TvType.Anime
        val description = document.select("div.full-text p").text()
        // val author = someInfo.select("strong:contains(Студія:)").next().html()
        val rating = document.selectFirst(".lexington-box > div:last-child span")?.text().toRatingInt()

        val recommendations = document.select(".horizontal ul").map {
            it.toSearchResponse()
        }

        someInfo.select(".full-info div.fi-col-item")
            .forEach {
                    ele ->
                when (ele.select("span").text()) {
                    //"Студія:" -> tags = ele.select("a").text().split(" / ")
                    "Жанр:" -> ele.select("a").map { tags = tags.plus(it.text()) }
                }
            }

        // Parse episodes
        var episodes: List<Episode> = emptyList()
        // Get Player URL
        val playerURl = document.select("input[value*=https://video.ufdub.com]").attr("value")

        // Parse only player
        val player = app.get(playerURl).document.select("script").html()

        // Parse all episodes
        val regexUFDubEpisodes = """https:\/\/ufdub.com\/video\/VIDEOS\.php\?(.*?)'""".toRegex()
        val matchResult = regexUFDubEpisodes.findAll(player)

        for (item: MatchResult in matchResult) {
            val parsedUrl = Uri.parse(item.value)
            episodes = episodes.plus(
                Episode(
                    item.value.dropLast(1), // Drop '
                    parsedUrl.getQueryParameter("Seriya")!!,
                )
            )
        }

        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
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

        val m3u8Url = app.get(data).url

        // Add as source
        callback(ExtractorLink(m3u8Url,"UFDub", m3u8Url, "https://dl.dropboxusercontent.com",
            Qualities.Unknown.value, false))
        return true
    }

    private fun decode(input: String): String{
        // Decoded string, thanks to Secozzi
        val hexRegex = Regex("\\\\u([0-9a-fA-F]{4})")
        return hexRegex.replace(input) { matchResult ->
            Integer.parseInt(matchResult.groupValues[1], 16).toChar().toString()
        }
    }
}