package com.lagradost

import com.lagradost.models.PlayerJson
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
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

    // Витягує значення поля file: з JS-коду плеєра.
    // Підтримує всі формати плеєра hdvbua.pro:
    //   1. file: '[{"title":"1 сезон","folder":[...]}]'         — серіал Сезон→Озвучка→Серія
    //   2. file: '[{"title":"QTV","folder":[{"title":"1 сезон"...}]}]' — серіал Озвучка→Сезон→Серія
    //   3. file: '[{"title":"Укр. Дуб.","file":"url.m3u8"}]'   — фільм з масивом озвучок
    //   4. file: "https://example.com/video.m3u8"               — пряме посилання (без JSON)
    private fun extractFileValue(scriptHtml: String): String {
        val keyRegex = Regex("""file\s*:\s*(['"])""")
        val match = keyRegex.find(scriptHtml) ?: return ""
        val quote = match.groupValues[1]
        val start = match.range.last + 1

        var i = start
        val sb = StringBuilder()
        while (i < scriptHtml.length) {
            val c = scriptHtml[i]
            if (c == '\\' && i + 1 < scriptHtml.length) {
                sb.append(scriptHtml[i + 1])
                i += 2
                continue
            }
            if (c.toString() == quote) break
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private val subtitleRegex = "subtitle\\s*:\\s*['\"]([^'\"]+)['\"]".toRegex()

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

        val description = document.selectFirst(".full_content-desc p")?.text()?.trim()
        val trailer = document.selectFirst("div#trailer_place iframe")?.attr("src").toString()
        val rating = document.selectFirst(".r_kp span, .r_imdb span")?.text()
        val actors = fullInfo[4].select("a").map { it.text() }

        val recommendations = document.select(".short.related_item").map {
            it.toSearchResponse()
        }

        // Завантажуємо плеєр і розбираємо JSON щоб зрозуміти реальну структуру.
        // Тип контенту визначаємо виключно по JSON, а не по жанровому тегу:
        // "аніме" може бути і серіалом (Наруто) і фільмом (Хлопчик і Чапля).
        val scriptHtml = app.get(playerUrl).document.select("script").html()
        val playerRawJson = extractFileValue(scriptHtml)
        val parsedJson = tryParseJson<List<PlayerJson>>(playerRawJson)

        // Визначення типу по структурі JSON:
        // parsedJson == null               -> пряме посилання на m3u8 -> фільм
        // first.file != null               -> масив озвучок фільму -> фільм
        // "сезон" є хоч де у 2 рівнях     -> серіал
        // /vod/ в playerUrl або жанр фільм -> фільм (гарантовано)
        val firstItem = parsedJson?.firstOrNull()
        val firstSubItem = firstItem?.folder?.firstOrNull()
        val isDefinitelyMovie = tags.contains("фільм") or tags.contains("мультьфільм") or playerUrl.contains("/vod/")

        val tvType = when {
            isDefinitelyMovie -> TvType.Movie
            parsedJson == null -> TvType.Movie
            firstItem?.file != null && firstItem.folder == null -> TvType.Movie  // масив озвучок фільму
            firstItem?.title?.contains("сезон", ignoreCase = true) == true -> TvType.TvSeries   // Формат А
            firstSubItem?.title?.contains("сезон", ignoreCase = true) == true -> TvType.TvSeries // Формат Б
            firstSubItem?.folder != null -> TvType.TvSeries  // є третій рівень -> серіал
            else -> TvType.Movie
        }

        // Return to app
        // Parse Episodes as Series
        return if (tvType == TvType.TvSeries) {
            val episodes = mutableListOf<Episode>()

            // Визначаємо ієрархію JSON:
            // Формат А: Сезон → Озвучка → Серія  (перший title = "1 сезон")
            //   [{"title":"1 сезон","folder":[{"title":"QTV","folder":[{"title":"1 серія","file":"url"}]}]}]
            // Формат Б: Озвучка → Сезон → Серія  (перший title = назва озвучки)
            //   [{"title":"QTV","folder":[{"title":"1 сезон","folder":[{"title":"1 серія","file":"url"}]}]}]
            //
            // Щоб уникнути дублювання серій в UI — одна кнопка на серію,
            // всі озвучки для неї парсяться в loadLinks.
            val isSeasonFirst = firstItem?.title?.contains("сезон", ignoreCase = true) == true

            if (isSeasonFirst) {
                // Формат А: Сезон → Озвучка → Серія
                parsedJson?.forEach { seasonItem ->
                    val seasonTitle = seasonItem.title
                    val seasonNum = seasonTitle.replace(" сезон", "").trim().toIntOrNull()

                    val episodeMap = linkedMapOf<String, String>()
                    seasonItem.folder.orEmpty().forEach { dub ->
                        dub.folder.orEmpty().forEach { episode ->
                            if (!episodeMap.containsKey(episode.title)) {
                                episodeMap[episode.title] = episode.poster ?: ""
                            }
                        }
                    }

                    episodeMap.forEach { (episodeTitle, episodePoster) ->
                        val episodeNum = episodeTitle.replace(" серія", "").trim().toIntOrNull()
                        val dataStr = "$seasonTitle|$episodeTitle|$playerUrl"
                        episodes.add(
                            newEpisode(dataStr) {
                                this.name = episodeTitle
                                this.season = seasonNum
                                this.episode = episodeNum
                                this.posterUrl = episodePoster.takeIf { it.isNotBlank() }
                                this.data = dataStr
                            }
                        )
                    }
                }
            } else {
                // Формат Б: Озвучка → Сезон → Серія
                parsedJson?.forEach { dubItem ->
                    dubItem.folder.orEmpty().forEach { seasonItem ->
                        val seasonTitle = seasonItem.title
                        val seasonNum = seasonTitle.replace(" сезон", "").trim().toIntOrNull()

                        val episodeMap = linkedMapOf<String, String>()
                        seasonItem.folder.orEmpty().forEach { episode ->
                            if (!episodeMap.containsKey(episode.title)) {
                                episodeMap[episode.title] = episode.poster ?: ""
                            }
                        }

                        episodeMap.forEach { (episodeTitle, episodePoster) ->
                            val episodeNum = episodeTitle.replace(" серія", "").trim().toIntOrNull()
                            val dataStr = "$seasonTitle|$episodeTitle|$playerUrl"
                            // Додаємо лише якщо ще немає (уникнення дублів між озвучками)
                            if (episodes.none { it.data == dataStr }) {
                                episodes.add(
                                    newEpisode(dataStr) {
                                        this.name = episodeTitle
                                        this.season = seasonNum
                                        this.episode = episodeNum
                                        this.posterUrl = episodePoster.takeIf { it.isNotBlank() }
                                        this.data = dataStr
                                    }
                                )
                            }
                        }
                    }
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = "$mainUrl$banner"
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else { // Parse as Movie.
            newMovieLoadResponse(title, url, TvType.Movie, "${title.replace("|", "")}|$playerUrl") {
                this.posterUrl = poster
                this.backgroundPosterUrl = "$mainUrl$banner"
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
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
        val dataList = data.split("|")

        val scriptHtml = app.get(dataList.last()).document.select("script").html()
        val playerRawJson = extractFileValue(scriptHtml)

        // Its film, parse m3u8
        // Три можливих формати для фільму/аніме:
        //   А) Масив озвучок: [{"title":"Укр. Дуб.","file":"url.m3u8","subtitle":"[UA]url.vtt"}]
        //   Б) Пряме посилання: "https://example.com/video.m3u8" (не JSON)
        if (dataList.size == 2) {
            val parsedDubs = tryParseJson<List<PlayerJson>>(playerRawJson)

            if (parsedDubs != null && parsedDubs.firstOrNull()?.file != null) {
                // Формат А — масив озвучок фільму, кожна з полем file (мастер m3u8).
                // Передаємо мастер-плейліст напряму як ExtractorLink — НЕ через M3u8Helper,
                // бо M3u8Helper розбиває мастер на окремі якості (-v відео без аудіо).
                parsedDubs.forEach { dub ->
                    val fileUrl = dub.file ?: return@forEach
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = dub.title,
                            url = fileUrl,
                            referer = "https://tortuga.wtf/",
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8
                        )
                    )

                    dub.subtitle?.takeIf { it.isNotBlank() }?.let { subtitleRaw ->
                        subtitleRaw.indexOf(']').takeIf { it > 0 }?.let { endIndex ->
                            subtitleCallback(
                                newSubtitleFile(
                                    subtitleRaw.substring(subtitleRaw.lastIndexOf('[') + 1, endIndex),
                                    subtitleRaw.substring(endIndex + 1)
                                )
                            )
                        }
                    }
                }
            } else {
                // Формат Б — пряме посилання (аніме-фільми, деякі інші).
                // Також передаємо мастер напряму, без розбивки M3u8Helper.
                if (playerRawJson.isNotBlank()) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = dataList[0],
                            url = playerRawJson,
                            referer = "https://tortuga.wtf/",
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                }

                // Субтитри для прямого формату — окремий ключ subtitle: "..." в JS
                val subtitleUrl = subtitleRegex.find(scriptHtml)?.groupValues?.get(1) ?: ""
                if (subtitleUrl.isNotBlank()) {
                    subtitleUrl.indexOf(']').takeIf { it > 0 }?.let { endIndex ->
                        subtitleCallback(
                            newSubtitleFile(
                                subtitleUrl.substring(subtitleUrl.lastIndexOf('[') + 1, endIndex),
                                subtitleUrl.substring(endIndex + 1)
                            )
                        )
                    }
                }
            }
            return true
        }

        // dataList[0] = seasonTitle ("1 сезон")
        // dataList[1] = episodeTitle ("1 серія")
        // dataList[2] = playerUrl
        val targetSeason = dataList[0]
        val targetEpisode = dataList[1]

        val parsedJson = tryParseJson<List<PlayerJson>>(playerRawJson) ?: return true
        val isSeasonFirst = parsedJson.firstOrNull()?.title?.contains("сезон", ignoreCase = true) == true

        if (isSeasonFirst) {
            // Формат А: Сезон → Озвучка → Серія
            // Проходимо всі озвучки для обраної серії і генеруємо окремий ExtractorLink на кожну
            parsedJson.forEach { seasonItem ->
                if (seasonItem.title != targetSeason) return@forEach
                seasonItem.folder.orEmpty().forEach { dub ->
                    val dubTitle = dub.title
                    dub.folder.orEmpty()
                        .filter { it.title == targetEpisode && !it.file.isNullOrBlank() }
                        .forEach { episode ->
                            val fileUrl = episode.file ?: return@forEach
                            M3u8Helper.generateM3u8(
                                source = dubTitle,
                                streamUrl = fileUrl,
                                referer = "https://tortuga.wtf/"
                            ).dropLast(1).forEach(callback)

                            episode.subtitle?.takeIf { it.isNotBlank() }?.let { subtitleRaw ->
                                subtitleRaw.indexOf(']').takeIf { it > 0 }?.let { endIndex ->
                                    subtitleCallback(
                                        newSubtitleFile(
                                            subtitleRaw.substring(subtitleRaw.lastIndexOf('[') + 1, endIndex),
                                            subtitleRaw.substring(endIndex + 1)
                                        )
                                    )
                                }
                            }
                        }
                }
            }
        } else {
            // Формат Б: Озвучка → Сезон → Серія (Наруто тощо)
            parsedJson.forEach { dubItem ->
                val dubTitle = dubItem.title
                dubItem.folder.orEmpty().forEach { seasonItem ->
                    if (seasonItem.title != targetSeason) return@forEach
                    seasonItem.folder.orEmpty()
                        .filter { it.title == targetEpisode && !it.file.isNullOrBlank() }
                        .forEach { episode ->
                            val fileUrl = episode.file ?: return@forEach
                            M3u8Helper.generateM3u8(
                                source = dubTitle,
                                streamUrl = fileUrl,
                                referer = "https://tortuga.wtf/"
                            ).dropLast(1).forEach(callback)

                            episode.subtitle?.takeIf { it.isNotBlank() }?.let { subtitleRaw ->
                                subtitleRaw.indexOf(']').takeIf { it > 0 }?.let { endIndex ->
                                    subtitleCallback(
                                        newSubtitleFile(
                                            subtitleRaw.substring(subtitleRaw.lastIndexOf('[') + 1, endIndex),
                                            subtitleRaw.substring(endIndex + 1)
                                        )
                                    )
                                }
                            }
                        }
                }
            }
        }
        return true
    }
}