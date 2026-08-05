package com.lagradost

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.mainPage
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.PlayerJson
import com.lagradost.nicehttp.Session
import okhttp3.FormBody
import org.jsoup.nodes.Element

class UAFlixProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://uafix.net"
    override var name = "UAFlix"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Cartoon,
        TvType.Movie,
        TvType.Anime
    )

    // Sections
    override val mainPage = mainPageOf(
        mainPage("$mainUrl/film/page/", "Фільми", horizontalImages = true),
        mainPage("$mainUrl/serials/page/", "Серіали", horizontalImages = true),
        mainPage("$mainUrl/dorama/page/", "Дорами", horizontalImages = true),
        mainPage("$mainUrl/cartoons/page/", "Мультфільми", horizontalImages = true),
        mainPage("$mainUrl/serials/multseial/page/", "Мультсеріали", horizontalImages = true),
        mainPage("$mainUrl/anime/page/", "Аніме", horizontalImages = true),
    )

    // Main Page
    private val animeSelector = ".video-item"
    private val titleSelector = ".vi-img"
    private val hrefSelector = titleSelector
    private val posterSelector = ".img-resp-h img"

    // Load info
    private val descriptionSelector = "#fdesc"
    private val ratingSelector = ".mediablock .rat-imdb"

    private val fileRegex = "file\\s*:\\s*['\"]([^'\"]+)['\"]".toRegex()
    private val subtitleRegex = "subtitle\\s*:\\s*['\"]([^'\"]*)['\"]".toRegex()

    // Cookies are kept here so the xfsort filter survives pagination.
    private val session by lazy { Session(app.baseClient) }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val baseUrl = request.data.replace("/page/", "/")
        // Date sort by default; in Серіали also hide anime via the site's xfsort filter.
        // The filter needs duplicate xf_field/xf_value keys, so a raw FormBody is used instead of a Map.
        val postBody = FormBody.Builder()
            .add("xf_sort", "get")
            .add("xf_field", "default")
            .add("xf_value", "date")
            .apply {
                if (request.name == "Серіали") {
                    add("xf_field", "-janr")
                    add("xf_value", "аніме")
                }
            }
            .build()

        // The site's xfsort filter lives in a server-side PHP session (PHPSESSID cookie).
        // The shared `app` client keeps no cookies, so a dedicated Session persists the cookie;
        // the filter is re-applied via POST before every page GET, since CS3 loads sections concurrently
        session.post(url = baseUrl, requestBody = postBody)
        val document = session.get(request.data + page).document

        val home = document
            .select(animeSelector)
            .filterNot {
                request.name == "Мультфільми" &&
                    it.selectFirst("$hrefSelector,.sres-wrap")?.attr("href").toString().contains("/serials/")
            }
            .map { it.toSearchResponse() }
        return newHomePageResponse(request, home)
    }

    private fun Element.toSearchResponse(): AnimeSearchResponse {
        val title = this.selectFirst("$titleSelector,.sres-img img")?.attr("alt")?.trim().toString()
        val href = this.selectFirst("$hrefSelector,.sres-wrap")?.attr("href").toString()
        val posterUrl = fixUrl(this.select("$posterSelector,.sres-img img").attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(isDub = true)
        }

    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            url = "$mainUrl/index.php?do=search&subaction=search&search_start=0&story=$query",
        ).document

        return document.select(".sres-wrap").map {
            it.toSearchResponse()
        }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        // Parse info

        val title = document.select(".fright h1").text().trim().replace("дивитись онлайн", "")
        val engTitle = document.select("span.eng-rus").text()
        var poster = fixUrl(document.select(".img-box img").attr("data-src"))
        if(poster.isNullOrBlank()){
            poster = fixUrl(document.select(".img-box img").attr("src"))
        }
        val tags = mutableListOf<String>()
        val actors = mutableListOf<String>()
        var year = "f".toIntOrNull()
        var contentRating = ""
        var countries: String? = null

        document.select(".fcols4 .finfo li").forEach { menu ->
            val label = menu.select("span").firstOrNull()?.text() ?: return@forEach
            when (label) {
                "Жанр:" -> menu.select("span[itemprop=genre]").forEach { tags.add(it.text()) }
                "В ролях:" -> menu.select("span[itemprop=actor]").forEach { actors.add(it.text()) }
                "Рік виходу:" -> {
                    year = menu.select(".year").text().toIntOrNull()
                    val text = menu.text()
                    if (text.contains(" / ")) {
                        contentRating = text.substringAfterLast(" / ").trim()
                    }
                }
                "Ориг. назва:" -> {
                    val text = menu.text()
                    if (text.contains(" / ")) {
                        contentRating = text.substringAfterLast(" / ").trim()
                    }
                }
                "Країна:" -> countries = menu.selectFirst(".country")?.text()
            }
        }

        var tvType = with(url){
            when{
                contains("serials") -> TvType.TvSeries
                contains("serials/multseial") -> TvType.Cartoon
                contains("film") -> TvType.Movie
                contains("cartoons") -> TvType.Movie
                contains("anime") -> TvType.Anime
                else -> TvType.TvSeries
            }
        }
        val description = document.selectFirst(descriptionSelector)?.text()?.trim()
        val plot = if (!countries.isNullOrBlank()) "<b>Країна: $countries.</b> $description" else description
        val rating = document.select(ratingSelector).text()

        // Parse episodes
        val episodes = mutableListOf<Episode>()
        val playerUrl = document.select(".video-box iframe").attr("src")
        if(playerUrl.isNullOrBlank()){ // Need parse episode list from site
            val pagination = if (document.select(".pagination li").size == 0) 1 else document.select(".pagination li").size
            for(i in 1..pagination){
                var episodesList = document
                if(i != 1){
                    episodesList = app.get("$url?page=$i").document
                }

                episodesList.select(".video-item").map { video_item ->
                    episodes.add(
                        newEpisode(video_item.select(".vi-img").attr("href")) {
                            this.name = video_item.select(".vi-rate").text()
                            val numbers = extractIntsFromString(video_item.select(".vi-title").text())
                            this.season = numbers.getOrNull(0)?.value?.toIntOrNull()
                            this.episode = numbers.getOrNull(1)?.value?.toIntOrNull()
                            this.posterUrl = fixUrl(video_item.select(".img-resp-h img").attr("data-src"))
                            this.data = video_item.select(".vi-img").attr("href")
                        }
                    )
                }
            }

        } else { // Player in site
            val playerRawJson = fileRegex.find(app.get(playerUrl, referer = "https://uafix.net").document.select("script").html())?.groupValues?.get(1) ?: ""

            tryParseJson<List<PlayerJson>>(playerRawJson)?.map { dubs -> // Dubs
                for(season in dubs.folder){                              // Seasons
                    for(episode in season.folder){                       // Episodes
                        val (seasonNumber, episodeNumber) =
                            parseEpisodeNumbers(season.title, episode.title)
                        episodes.add(
                            newEpisode("${season.title}, ${episode.title}, $playerUrl") {
                                this.name = episode.title
                                this.season = seasonNumber
                                this.episode = episodeNumber
                                this.posterUrl = episode.poster
                                this.data = "${season.title}, ${episode.title}, $playerUrl"
                            }
                        )
                    }
                }
            }
        }


        // Parse Episodes as Series
        return if (tvType != TvType.Movie) {

            newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = poster
                this.engName = engTitle
                this.year = year
                this.plot = plot
                this.tags = tags
                this.contentRating = contentRating
                this.score = Score.from10(rating)
                addEpisodes(DubStatus.Dubbed, episodes.sortedBy { it.episode })
                addActors(actors)
            }
        } else { // Parse as Movie.

            newMovieLoadResponse(title, url, tvType, url) {
                this.posterUrl = poster
                this.name = title
                this.year = year
                this.plot = plot
                this.tags = tags
                this.contentRating = contentRating
                this.score = Score.from10(rating)
                addActors(actors)
            }
        }
    }

    // It works when I click to view the series
    override suspend fun loadLinks(
        data: String, // (Serial) [Season Index, Episode Name, url] | (Film) [Title, Player Url]
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split(", ")

        if(dataList.size == 1){
            var playerUrl = app.get(data).document.select(".video-box iframe").attr("src")
            if(playerUrl.startsWith("//")){
                playerUrl = "https:$playerUrl"
            }
            if(playerUrl.contains("/vod/")){
                val playerDocument = app.get(playerUrl,
                        headers = mapOf(
                                "Referer" to "https://uafix.net/",
                        )).document
                val playerRawJson = fileRegex.find(playerDocument.select("script").html())?.groupValues?.get(1) ?: ""

                val subtitleString = subtitleRegex.find(playerDocument.select("script").html())?.groupValues?.get(1) ?: ""

                M3u8Helper.generateM3u8(
                        source = "UAFlix",
                        streamUrl = playerRawJson,
                        referer = "https://tortuga.wtf/"
                ).dropLast(1).forEach(callback)

                parseUAFlixSubtitle(subtitleString)?.let { subtitle ->
                    subtitleCallback.invoke(newSubtitleFile(subtitle.language, subtitle.url))
                }

                return true
            }
            val playerRawJson = fileRegex.find(app.get(playerUrl, referer = "https://uafix.net").document.select("script").html())?.groupValues?.get(1) ?: ""
            tryParseJson<List<PlayerJson>>(playerRawJson)?.map { dubs ->   // Dubs
                for(season in dubs.folder){                                // Seasons
                    val episode = season.folder.firstOrNull() ?: continue
                    if (episode.file.isBlank()) continue
                    // Add as source
                    M3u8Helper.generateM3u8(
                            source = dubs.title,
                            streamUrl = episode.file,
                            referer = "https://tortuga.wtf/"
                    ).dropLast(1).forEach(callback)

                    parseUAFlixSubtitle(episode.subtitle)?.let { subtitle ->
                        subtitleCallback.invoke(newSubtitleFile(subtitle.language, subtitle.url))
                    }
                }
            }

            return true
        }


        val playerRawJson = fileRegex.find(app.get(dataList[2], referer = "https://uafix.net").document.select("script").html())?.groupValues?.get(1) ?: ""
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
                            ).dropLast(1).forEach(callback)

                            parseUAFlixSubtitle(episode.subtitle)?.let { subtitle ->
                                subtitleCallback.invoke(newSubtitleFile(subtitle.language, subtitle.url))
                            }
                        }
                    }
                }
            }
        }

        return true
    }

    private fun extractIntsFromString(string: String): List<MatchResult> {
        return Regex("(\\d+)").findAll(string).toList()
    }
}

internal fun parseEpisodeNumbers(seasonTitle: String, episodeTitle: String): Pair<Int?, Int?> {
    val numberRegex = Regex("\\d+")
    return numberRegex.find(seasonTitle)?.value?.toIntOrNull() to
        numberRegex.find(episodeTitle)?.value?.toIntOrNull()
}
