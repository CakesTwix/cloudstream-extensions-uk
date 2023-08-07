package com.lagradost

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

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
        "$mainUrl/cartoon-serial/page/" to "Мультсеріали",
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
            url = "$mainUrl/index.php?do=search",
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to query.replace(" ", "+")
            )
        ).document

        return document.select(".short").map {
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
        val tags = mutableListOf<String>()
        val year = someInfo.select("strong:contains(Рік випуску аніме:)").next().html().toIntOrNull()

        val description = document.select("div.full-text p").text()
        // val author = someInfo.select("strong:contains(Студія:)").next().html()
        val rating = toRatingInt(document.select(".fp-rate"))

        val recommendations = document.select(".rel").map {
            newMovieSearchResponse(it.select(".img-box img").attr("alt"), it.attr("href"), TvType.Anime) {
                this.posterUrl = "$mainUrl${it.select(".img-box img").attr("src")}"
            }
        }

        someInfo.select(".full-info div.fi-col-item")
            .forEach {
                    ele ->
                when (ele.select("span").text()) {
                    //"Студія:" -> tags = ele.select("a").text().split(" / ")
                    "Жанр:" -> ele.select("a").map { tags.add(it.text()) }
                }
            }

        val tvType = with(tags){
            when{
                contains("Фільми") -> TvType.Movie
                contains("Мультсеріали") -> TvType.Cartoon
                contains("Серіали") -> TvType.TvSeries
                contains("Мультфільми") -> TvType.Movie
                contains("Аніме") -> TvType.Anime
                contains("Дорами") -> TvType.AsianDrama
                else -> TvType.TvSeries
            }
        }

        // Parse episodes
        val episodes = mutableListOf<Episode>()
        // Get Player URL
        val playerURl = document.select("input[value*=https://video.ufdub.com]").attr("value")

        // Parse only player
        val player = app.get(playerURl).document.select("script").html()

        // Parse all episodes
        val regexUFDubEpisodes = """https:\/\/ufdub.com\/video\/VIDEOS\.php\?(.*?)'""".toRegex()
        val matchResult = regexUFDubEpisodes.findAll(player)

        // Drop trailers from episodes
        matchResult.filter { !(Uri.parse(it.value).getQueryParameter("Seriya")!!.contains("Трейлер")) }
            .forEach { item ->

                val parsedUrl = Uri.parse(item.value)
                episodes.add(
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

    private fun toRatingInt(el: Elements): Int? {
        // +54
        val raterate = el.select(".ratingtypeplusminus").text().toInt();
        // 60
        val ratecount = el.select("span").last()!!.text().toInt();

        val minusik = (ratecount - raterate) / 2;
        val plusik = ratecount - minusik;

        return (plusik.toFloat() / ratecount.toFloat() * 10).toString().toRatingInt();
    }
}