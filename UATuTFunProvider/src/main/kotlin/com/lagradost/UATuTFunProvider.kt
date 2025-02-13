package com.lagradost

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import org.jsoup.nodes.Element

class UATuTFunProvider : MainAPI() {

    private val movieSelector = "section.sect.flex-grow-1"

    private val interceptor = CloudflareKiller()

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
        "$mainUrl/serie/" to "Серіали",
        "$mainUrl/serials/cartoon/series/" to "Мультсеріали",
        "$mainUrl/cartoon/" to "Мультфільми",
        "$mainUrl/anime/" to "Аніме",
        "$mainUrl/film/" to "Фільми"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val mainPage = document.select(movieSelector).map {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, mainPage)
    }


    private fun Element.toSearchResponse(): MovieSearchResponse {
        val title = ""
        val url = ""
        val posterUrl = ""
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

}