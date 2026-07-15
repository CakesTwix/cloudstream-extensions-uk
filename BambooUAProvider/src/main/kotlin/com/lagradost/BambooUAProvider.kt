package com.lagradost

import com.google.gson.Gson
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Element

class BambooUAProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://bambooua.com"
    override var name = "BambooUA"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AsianDrama,
    )

    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/cinema/page/" to "Фільми",
        "$mainUrl/dorama/page/" to "Дорами",
        "$mainUrl/anime/page/" to "Аніме",
        "$mainUrl/lakorn/page/" to "Лакорн",
        "$mainUrl/voice/page/" to "Озвучення",
        "$mainUrl/tv-show/page/" to "ТВ-шоу",
        "$mainUrl/done/page/" to "Завершені",
        "$mainUrl/world-bl/page/" to "Світ ЛГБТ",
        "$mainUrl/now/page/" to "Поточні",
    )

    // Main Page — оновлені селектори
    private val animeSelector = "article.swiper-slide"
    private val titleSelector = "h2.label-3"
    private val hrefSelector = "a.link-title"
    private val posterSelector = "div.poster img"

    // Load info
    private val genresSelector = "span.full_cat a"
    private val yearSelector = ".trending-info .text-detail span.badge-danger"

    // Regex для витягування playlist JSON зі скрипту сторінки
    private val playlistRegex = Regex("""const playlist = (\[.*?]);""", RegexOption.DOT_MATCHES_ALL)

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
        val posterUrl = this.selectFirst(posterSelector)?.attr("src")?.let {
            if (it.startsWith("http")) it else "$mainUrl$it"
        }

        val subText = this.select(".caption-2").firstOrNull { it.text().contains("Суб.") }?.text() ?: ""
        val dubText = this.select(".caption-2").firstOrNull { it.text().contains("Озв.") }?.text() ?: ""
        val subCount = subText.filter { it.isDigit() }.toIntOrNull()
        val dubCount = dubText.filter { it.isDigit() }.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(dubCount != null, subCount != null, dubCount, subCount)
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

        // Parse metadata з JSON-LD
        val gJson = Gson().fromJson(document.select("script[type*=json]").html(), JSONModel::class.java)

        val title = gJson.graph[0].name
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.poster img")?.attr("src")
        val tags = document.select(genresSelector).map { it.text() }
        val year = document.select(yearSelector).text().toIntOrNull()

        val tvType = with(tags) {
            when {
                contains("Аніме") -> TvType.Anime
                contains("Кіно") -> TvType.Movie
                else -> TvType.AsianDrama
            }
        }
        val description = gJson.graph[0].description

        val recommendations = document.select(".favorites-slider article.swiper-slide").map {
            it.toSearchResponse()
        }

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        // Витягуємо playlist JSON зі скрипту сторінки
        val pageHtml = document.html()
        val playlistJson = playlistRegex.find(pageHtml)?.groupValues?.get(1)

        if (playlistJson != null) {
            val playlist = Gson().fromJson(playlistJson, Array<PlaylistGroup>::class.java)
            playlist.forEach { group ->
                val isDub = group.title.contains("Озвучення") || group.title.contains("Дубляж")
                val isSub = group.title.contains("Субтитри")
                group.folder.forEachIndexed { index, episode ->
                    val ep = newEpisode(episode.file) {
                        this.name = episode.title
                        this.episode = index + 1
                        this.data = episode.file
                    }
                    when {
                        isDub -> dubEpisodes.add(ep)
                        isSub -> subEpisodes.add(ep)
                    }
                }
            }
        }

        // Return to app
        return if (tvType != TvType.Movie) {
            newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                this.addEpisodes(DubStatus.Dubbed, dubEpisodes)
                this.addEpisodes(DubStatus.Subbed, subEpisodes)
            }
        } else {
            newMovieLoadResponse(title, url, tvType, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    // It works when I click to view the series
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Movie — data це URL сторінки
        if (data.startsWith("https://bambooua.com")) {
            val document = app.get(data).document
            val pageHtml = document.html()
            val playlistJson = playlistRegex.find(pageHtml)?.groupValues?.get(1)
            if (playlistJson != null) {
                val playlist = Gson().fromJson(playlistJson, Array<PlaylistGroup>::class.java)
                playlist.forEach { group ->
                    group.folder.forEach { episode ->
                        M3u8Helper.generateM3u8(
                            source = group.title,
                            streamUrl = episode.file,
                            referer = "$mainUrl/"
                        ).forEach(callback)
                    }
                }
            }
            return true
        }

        // Serial — data це пряме m3u8 посилання
        M3u8Helper.generateM3u8(
            source = "BambooUA",
            streamUrl = data,
            referer = "$mainUrl/"
        ).forEach(callback)

        return true
    }

    data class PlaylistGroup(
        val title: String,
        val folder: List<PlaylistEpisode>
    )

    data class PlaylistEpisode(
        val title: String,
        val file: String
    )
}
