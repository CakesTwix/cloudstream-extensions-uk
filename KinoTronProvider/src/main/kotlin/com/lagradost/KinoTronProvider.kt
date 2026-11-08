package com.lagradost

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.PlayerJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class KinoTronProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://kinotron.tv"
    override var name = "KinoTron"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.Cartoon,
        TvType.TvSeries,
        TvType.Anime
    )

    val fileRegex = "file\\s*:\\s*'([^']+)'".toRegex()

    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/films/page/" to "Фільми",
        "$mainUrl/serials/page/" to "Серіали",
        "$mainUrl/cartoons/page/" to "Мультфільми",
        "$mainUrl/cartoon-series/page/" to "Мультсеріали",
        "$mainUrl/anime/page/" to "Аніме",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document

        val home = document.select(".th-item").map {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val title = this.select(".th-title").text()
        val href = this.select(".th-in").attr("href")
        val posterUrl = mainUrl + this.select(".img-fit img").attr("data-src")

        return newMovieSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }

    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            url = "$mainUrl/index.php?do=search",
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to query.replace(" ", "+")
            )
        ).document

        return document.select(".th-item").map {
            it.toSearchResponse()
        }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document


        // Parse info
        val title = cleanKinoTronTitle(document.select(".full h1").text())
        val poster = mainUrl + document.select(".img-box img").attr("data-src")
        val tags = document.select(".flist li")[2].select("a").map { it.text() }

        val year = document.select(".flist li")[0].select("a").text().toIntOrNull()

        var tvType = with(document.select("div.fsubtitle").text()) {
            when {
                contains("Аніме") -> TvType.Anime
                contains("Серіал") -> TvType.TvSeries
                contains("Мультсеріал") -> TvType.Cartoon
                else -> {
                    TvType.Movie
                }
            }
        }
        val description = document.select(".full-text").text()
        // val author = someInfo.select("strong:contains(Студія:)").next().html()
        val rating = document.selectFirst(".fqualityimdb")?.text()

        // Parse episodes
        val episodes = mutableListOf<Episode>()
        val playerUrl = document.select("div.video-box iframe").attr("data-src").ifBlank {
            document.select("div.video-box iframe").attr("src")
        }
        val trailer = extractKinoTronTrailer(document)
        val isPlayable = isKinoTronPlayableMainPlayer(playerUrl, trailer)
        val isComingSoon = !isPlayable || isKinoTronComingSoon(document)

        if (isPlayable) {
            if (playerUrl.contains("/vod/")) { tvType = TvType.Movie }
            // Ashdi явно розділяє фільми та серіали адресами /vod/ і /serial/.
            // Деякі сторінки KinoTron мають неточний fsubtitle, тому довіряємо плеєру.
            if (tvType == TvType.Movie && isKinoTronSeriesPlayerUrl(playerUrl)) {
                tvType = TvType.TvSeries
            }
        }
        // Log.d("load-debug", playerUrl)
        // Return to app
        // Parse Episodes as Series
        return if (tvType != TvType.Movie) {
            if (isPlayable) {
                val playerRawJson = fileRegex.find(app.get(playerUrl).document.select("script").html())?.groups?.get(1)?.value ?: ""

                AppUtils.tryParseJson<List<PlayerJson>>(playerRawJson)?.map { dubs -> // Dubs
                    for(season in dubs.folder){                              // Seasons
                        for(episode in season.folder){                       // Episodes
                            episodes.add(
                                 newEpisode("${season.title}|${episode.title}|$playerUrl") {
                                    this.name = episode.title
                                    this.season = season.title.replace(" Сезон ","").toIntOrNull()
                                    this.episode = episode.title.replace("Серія ","").toIntOrNull()
                                    this.posterUrl = episode.poster
                                    this.data = "${season.title}|${episode.title}|$playerUrl"
                                }
                            )
                        }
                    }
                }
            }
            val response = newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                trailer?.let { addTrailer(it) }
            }
            if (isComingSoon) response.comingSoon = true
            response
        } else { // Parse as Movie.
            val movieData = if (isPlayable) "${title.replace("|", "")}|$playerUrl" else url
            newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.comingSoon = isComingSoon
                trailer?.let { addTrailer(it) }
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
        val dataList = data.split("|")

        // Its film, parse one m3u8
        if(dataList.size == 2){
            val streamPlayerUrl = dataList[1].trim()
            if (!isKinoTronPlayableMainPlayer(streamPlayerUrl, null)) {
                return false
            }

            val m3u8Url = fileRegex.find(app.get(streamPlayerUrl).document.select("script").html())?.groups?.get(1)?.value ?: ""
            M3u8Helper.generateM3u8(
                source = dataList[0],
                streamUrl = m3u8Url,
                referer = mainUrl
            ).dropLast(1).forEach(callback)

            return true
        }

        if (dataList.size == 3) {
            val serialPlayerUrl = dataList[2].trim()
            if (!isKinoTronPlayableMainPlayer(serialPlayerUrl, null)) {
                return false
            }

            val playerRawJson = fileRegex.find(app.get(serialPlayerUrl).document.select("script").html())?.groups?.get(1)?.value ?: ""

            AppUtils.tryParseJson<List<PlayerJson>>(playerRawJson)?.map { dubs ->   // Dubs
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
                            }
                        }
                    }
                }
            }
            return true
        }
        return false
    }
}

/** Не вважає YouTube-трейлер основним потоком для KinoTron. */
internal fun isKinoTronPlayableMainPlayer(playerUrl: String?, trailerUrl: String?): Boolean {
    val player = playerUrl?.trim().orEmpty()
    if (player.isBlank()) return false
    val duplicateTrailer = trailerUrl?.trim()?.equals(player, ignoreCase = true) == true
    val isYoutube = player.contains("youtube.com", ignoreCase = true) ||
        player.contains("youtu.be", ignoreCase = true)
    return !isYoutube && !duplicateTrailer
}

/** Визначає, чи є сторінка анонсом / прем'єрою на KinoTron. */
internal fun isKinoTronComingSoon(document: Document): Boolean {
    val messageText = document.select(".fmessage").text()
    if (messageText.contains("скоро вийде", ignoreCase = true) ||
        messageText.contains("анонс", ignoreCase = true) ||
        messageText.contains("трейлер", ignoreCase = true)
    ) {
        return true
    }
    return false
}

/** Визначає серіальний Ashdi-плеєр незалежно від підпису сторінки KinoTron. */
internal fun isKinoTronSeriesPlayerUrl(playerUrl: String?): Boolean =
    playerUrl?.contains("/serial/", ignoreCase = true) == true

/** Витягує трейлер із явного trailer-box, відповідного табу або плеєра YouTube KinoTron. */
internal fun extractKinoTronTrailer(document: Document): String? {
    fun normalize(raw: String): String? {
        val value = raw.trim()
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://") || value.startsWith("https://") -> value
            else -> null
        }
    }

    val explicitTrailer = document
        .select(".trailer-box iframe[src], .trailer-box iframe[data-src], .trailer-box video[src], .trailer-box source[src]")
        .asSequence()
        .map { it.attr("src").ifBlank { it.attr("data-src") } }
        .mapNotNull(::normalize)
        .firstOrNull()
    if (explicitTrailer != null) return explicitTrailer

    val trailerIndex = document
        .select(".tabs-sel span")
        .indexOfFirst { it.text().contains("трейлер", ignoreCase = true) }
    if (trailerIndex >= 0) {
        val iframe = document
            .select(".tabs-b.video-box")
            .getOrNull(trailerIndex)
            ?.selectFirst("iframe[src], iframe[data-src], video[src], source[src]")
        val tabTrailer = iframe?.let { normalize(it.attr("src").ifBlank { it.attr("data-src") }) }
        if (tabTrailer != null) return tabTrailer
    }

    val candidate = document.select("div.video-box iframe[src], div.video-box iframe[data-src]")
        .asSequence()
        .map { it.attr("src").ifBlank { it.attr("data-src") } }
        .mapNotNull(::normalize)
        .firstOrNull { it.contains("youtube.com", ignoreCase = true) || it.contains("youtu.be", ignoreCase = true) }

    return candidate
}

/** Очищає SEO хвости на кшталт "дивитись онлайн" з заголовка KinoTron. */
internal fun cleanKinoTronTitle(rawTitle: String): String {
    return com.lagradost.common.TitleUtils.stripSeo(rawTitle)
}
