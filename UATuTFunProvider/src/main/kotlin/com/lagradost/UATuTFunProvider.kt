package com.lagradost

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import org.jsoup.nodes.Element

class UATuTFunProvider : MainAPI() {

    private val movieSelector = "#dle-content"
    private val titleSelector = "div.poster__desc > h3 > a > span"
    private val videoUrlSelector = "data-url"
    private val posterUrlSelector =
        "div.poster__img.img-responsive.img-responsive--portrait.img-fit-cover.anim > img"
    private val searchMovieSelector = "div.poster.grid-item"

    // Basic Info
    override var mainUrl = "https://uk.uatut.fun"
    override var name = "UATuT"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Cartoon,
        TvType.Movie,
        TvType.Anime
    )

    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/serie/page/" to "Серіали",
        "$mainUrl/cartoon/series/page/" to "Мультсеріали",
        "$mainUrl/cartoon/page/" to "Мультфільми",
        "$mainUrl/anime/page/" to "Аніме",
        "$mainUrl/film/page/" to "Фільми"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val first = document.select(movieSelector)
            .first() ?: throw ErrorLoadingException("Can't find main page")

        val mainPage = first.children().map {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, mainPage)
    }

    private fun Element.toSearchResponse(): MovieSearchResponse {
        val title = this.select(titleSelector).text()
        val url = this.attr(videoUrlSelector)
        val posterUrl = mainUrl +
                this.select(posterUrlSelector)
                    .attr("data-src")
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response =
            app.post("$mainUrl/index.php?do=search&subaction=search&story=$query").document
        val select = response.select(searchMovieSelector)
        val map = select.map {
            it.toSearchResponse()
        }
        return map
    }

}