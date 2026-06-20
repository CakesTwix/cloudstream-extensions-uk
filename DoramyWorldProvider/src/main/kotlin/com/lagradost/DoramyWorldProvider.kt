package com.lagradost

import com.google.gson.Gson
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Element

class DoramyWorldProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://doramy.world"
    override var name = "DoramyWorld"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Movie,
    )

    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/dorama/page/" to "Дорами",
        "$mainUrl/film/page/" to "Фільми",
        "$mainUrl/show/page/" to "Розважальні шоу",
    )

    // Selectors (lists / search)
    private val cardSelector = "article.type-dorama, article.type-film, article.type-show"

    // Selectors (title page)
    private val titleSelector = "h1.project-title"
    private val genresSelector = "a[href*=/genre/]"
    private val infoRowSelector = "li.item"
    private val descriptionSelector = "div.about-text-holder p"

    // ashdi referer (player host that serves the .m3u8)
    private val ashdiReferer = "https://ashdi.vip/"

    // Regex: serial playlist  ->  file:'[ ... json ... ]'
    private val serialPlaylistRegex =
        Regex("""file:'(\[.*])'""", RegexOption.DOT_MATCHES_ALL)

    // Regex: any direct .m3u8 link (used for /vod/ films)
    private val m3u8Regex = Regex("""(https?://[^'"]+\.m3u8[^'"]*)""")

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page + "/").document
        val home = document.select(cardSelector).mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("h3.post-title a") ?: return null
        val href = link.attr("href")
        if (href.isBlank()) return null
        val title = link.selectFirst("span")?.text()?.trim()
            ?: link.text().trim()
        val poster = this.selectFirst("img")?.attr("src")

        return if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newAnimeSearchResponse(title, href, TvType.AsianDrama) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document
        return document.select(cardSelector).mapNotNull { it.toSearchResponse() }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(titleSelector)?.text()
            ?.substringBefore("/")?.trim().orEmpty()
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val tags = document.select(genresSelector).map { it.text() }
        val year = document.select(infoRowSelector)
            .firstOrNull { it.text().contains("Рік") }
            ?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val description = document.select(descriptionSelector)
            .joinToString("\n") { it.text() }.ifBlank { null }
        val recommendations = document.select(cardSelector).mapNotNull { it.toSearchResponse() }

        val isMovie = url.contains("/film/")
        val iframe = document.selectFirst("iframe[src*=ashdi]")?.attr("src")

        // ---- Movie (ashdi /vod/) ----
        if (isMovie) {
            val streamUrl = iframe?.let { extractMovieStream(it) } ?: ""
            return newMovieLoadResponse(title, url, TvType.Movie, streamUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        }

        // ---- Series (ashdi /serial/) ----
        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        if (iframe != null && iframe.contains("/serial/")) {
            val ashdiHtml = app.get(iframe, referer = "$mainUrl/").text
            val json = serialPlaylistRegex.find(ashdiHtml)?.groupValues?.get(1)
            if (json != null) {
                val groups = Gson().fromJson(json, Array<AshdiItem>::class.java)
                groups.forEach { group ->
                    val isSub = group.title.contains("Суб", ignoreCase = true)
                    val target = if (isSub) subEpisodes else dubEpisodes
                    val seasons = group.folder ?: emptyList()
                    seasons.forEachIndexed { seasonIndex, season ->
                        // normal case: season.folder = episodes;
                        // fallback: season itself is already an episode
                        val episodes = season.folder ?: listOf(season)
                        episodes.forEach { ep ->
                            val file = ep.file ?: return@forEach
                            target.add(
                                newEpisode(file) {
                                    this.name = ep.title
                                    this.season = seasonIndex + 1
                                    this.episode = ep.title.filter { it.isDigit() }.toIntOrNull()
                                    this.data = file
                                }
                            )
                        }
                    }
                }
            }
        }

        return newAnimeLoadResponse(title, url, TvType.AsianDrama) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
            this.addEpisodes(DubStatus.Dubbed, dubEpisodes)
            this.addEpisodes(DubStatus.Subbed, subEpisodes)
        }
    }

    // Films: fetch the ashdi /vod/ page and pull the single .m3u8
    private suspend fun extractMovieStream(iframeUrl: String): String? {
        val html = app.get(iframeUrl, referer = "$mainUrl/").text
        return m3u8Regex.find(html)?.groupValues?.get(1)
    }

    // data is always a direct .m3u8 (episode.file or movie stream)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        M3u8Helper.generateM3u8(
            source = name,
            streamUrl = data,
            referer = ashdiReferer
        ).forEach(callback)
        return true
    }

    // Recursive node: a group, a season, or an episode.
    // Groups/seasons have `folder`; episodes have `file`.
    data class AshdiItem(
        val title: String = "",
        val file: String? = null,
        val folder: List<AshdiItem>? = null
    )
}