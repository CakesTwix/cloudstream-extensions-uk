package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.extractors.Mp4Upload
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.extractors.AshdiExtractor
import com.lagradost.extractors.csstExtractor
import com.lagradost.models.Ajax
import com.lagradost.models.Link
import com.lagradost.models.PlayerJson
import com.lagradost.models.videoConstructor
import java.util.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnitubeinuaProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://anitube.in.ua"
    override var name = "Anitubeinua Beta"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = true
    override val supportedTypes =
        setOf(
            TvType.AnimeMovie,
            TvType.Anime,
        )

    // Sections
    override val mainPage =
        mainPageOf(
            "$mainUrl/anime/page/" to "Нові",
            "$mainUrl/f/sort=rating/order=desc/page/" to "Популярні",
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document

        val home = document.select(".story").map { it.toSearchResponse() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): AnimeSearchResponse {
        val title = this.selectFirst(".story_c h2 a, div.text_content a")?.text()?.trim().toString()
        val href = this.selectFirst(".story_c h2 a, div.text_content a")?.attr("href").toString()
        val posterUrl =
            mainUrl + this.selectFirst(".story_c_l span.story_post img, a img")?.attr("src")

        var isSub = this.select(".box .sub").isNotEmpty()
        var isDub = this.select(".box .ukr").isNotEmpty()
        if (!isSub && !isDub) {
            isSub = true
            isDub = true
        }
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(isDub, isSub)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document =
            app.post(
                url = mainUrl,
                data =
                mapOf(
                    "do" to "search",
                    "subaction" to "search",
                    "story" to query.replace(" ", "+")
                )
            )
                .document

        return document.select("article.story").map { it.toSearchResponse() }
    }

    // Detailed information
    override suspend fun load(url: String): AnimeLoadResponse {
        val document = app.get(url).document

        val someInfo = document.select(".story_c_r")

        // Parse info
        val title = document.selectFirst(".story_c h2")?.text()?.trim().toString()
        val poster =
            mainUrl + document.selectFirst(".story_c_left span.story_post img")?.attr("src")
        val tags = someInfo.select("a[href*=/anime/]").map { it.text() }
        val year = someInfo.select("a[href*=/xfsearch/year/]").text().toIntOrNull()

        val tvType = TvType.Anime
        val description = document.selectFirst("div.my-text")?.text()?.trim()
        // val author = someInfo.select("strong:contains(Студія:)").next().html()
        val trailer = document.selectFirst(".rcol a.rollover")?.attr("href").toString()
        val rating =
            document.selectFirst(".lexington-box > div:last-child span")?.text().toRatingInt()

        val recommendations = document.select(".horizontal ul li").map { it.toSearchResponse() }

        // Return to app
        // Players, Episodes, Number of episodes
        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()
        val id = url.split("/").last().split("-").first()

        val ajax =
            fromPlaylistAjax(
                "$mainUrl/engine/ajax/playlists.php?news_id=$id&xfield=playlist&time=${Date().time}"
            )

        if (!ajax.isNullOrEmpty()) { // Ajax list
            ajax
                .groupBy { it.name }
                .forEach { episodes -> // Group by name
                    episodes.value.forEach lit@{
                        // UFDub player, drop
                        if (it.name == "ПЛЕЙЛИСТ") return@lit

                        if (it.urls.isDub) {
                            dubEpisodes.add(
                                Episode(
                                    "${it.name}, $id, ${it.urls.isDub}",
                                    it.name,
                                    episode = it.numberEpisode
                                )
                            )
                        } else {
                            subEpisodes.add(
                                Episode(
                                    "${it.name}, $id, ${it.urls.isDub}",
                                    it.name,
                                    episode = it.numberEpisode
                                )
                            )
                        }
                    }
                }
        } else {
            document.select("script").map { script ->
                if (script.data().contains("RalodePlayer.init(")) {
                    val episodesList = fromVideoContructor(script)

                    episodesList.forEach { episode ->
                        // UFDub player, drop
                        var varEpisodeNumber = episode.episodeNumber
                        if (episode.episodeName == "ПЛЕЙЛИСТ") return@forEach
                        if (varEpisodeNumber == null) {
                            varEpisodeNumber = episodesList.last().episodeNumber?.plus(1)
                        }
                        dubEpisodes.add(
                            Episode(
                                "$varEpisodeNumber, $url",
                                episode.episodeName,
                                episode = varEpisodeNumber,
                            )
                        )
                    }
                }
            }
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.rating = rating
            addTrailer(trailer)
            this.recommendations = recommendations
            addEpisodes(DubStatus.Dubbed, dubEpisodes)
            addEpisodes(DubStatus.Subbed, subEpisodes)
        }
    }

    // It works when I click to view the series
    override suspend fun loadLinks(
        data: String, // (Ajax) Name, id title, isDub | (Two) Episode name, url title
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split(", ")
        // Log.d("CakesTwix-Debug", data)
        if (dataList[1].toIntOrNull() != null) { // Its ajax list

            val ajax =
                fromPlaylistAjax(
                    "$mainUrl/engine/ajax/playlists.php?news_id=${dataList[1]}&xfield=playlist&time=${Date().time}"
                )

            // Filter by name and isDub
            ajax
                ?.filter { it.name == dataList[0] }
                ?.filter { it.urls.isDub == dataList[2].toBoolean() }
                ?.forEach {
                    // Get m3u8 url
                    with(it) {
                        when {
                            it.urls.url.contains("https://tortuga.wtf/vod/") -> {
                                M3u8Helper.generateM3u8(
                                    source = "${it.urls.playerName} (${it.urls.name})",
                                    streamUrl = AshdiExtractor().ParseM3U8(this.urls.url),
                                    referer = "https://tortuga.wtf/"
                                )
                                    .forEach(callback)
                            }
                            it.urls.url.contains("https://ashdi.vip/vod") -> {
                                M3u8Helper.generateM3u8(
                                    source = "${it.urls.playerName} (${it.urls.name})",
                                    streamUrl = AshdiExtractor().ParseM3U8(this.urls.url),
                                    referer = "https://qeruya.cyou"
                                )
                                    .forEach(callback)
                            }
                            it.urls.url.contains("https://www.udrop.com") -> {
                                callback.invoke(
                                    ExtractorLink(
                                        this.urls.url,
                                        name = "${it.urls.playerName} (${it.urls.name})",
                                        this.urls.url,
                                        "",
                                        0,
                                        isM3u8 = false,
                                    )
                                )
                            }
                            it.urls.url.contains("https://csst.online/embed/") || it.urls.url.contains("https://monstro.site/embed/") -> {
                                csstExtractor().ParseUrl(it.urls.url).split(",").forEach { csstUrl
                                    ->
                                    callback.invoke(
                                        ExtractorLink(
                                            this.urls.url,
                                            name = "${it.urls.playerName} (${it.urls.name}) ${csstUrl.substringBefore("]").drop(1)}",
                                            csstUrl.substringAfter("]"),
                                            "",
                                            0,
                                            isM3u8 = false,
                                        )
                                    )
                                }
                            }
                            it.urls.url.contains("https://www.mp4upload.com/") -> {
                                Mp4Upload().getUrl(it.urls.url)?.forEach { extlink ->
                                    callback.invoke(
                                        ExtractorLink(
                                            extlink.source,
                                            "${it.urls.playerName} (${it.urls.name})",
                                            extlink.url,
                                            extlink.referer,
                                            extlink.quality,
                                            extlink.type,
                                            extlink.headers,
                                        )
                                    )
                                }
                            }
                            else -> {}
                        }
                    }
                }
        } else {
            val document = app.get(dataList[1]).document
            document.select("script").map { script ->
                if (script.data().contains("RalodePlayer.init(")) {
                    var latestNumber: Int? = 0
                    fromVideoContructor(script).forEach { dub ->
                        if (dub.episodeName == "ПЛЕЙЛИСТ") return@forEach

                        // Parse by number episode
                        // If null, just add +1
                        if (dub.episodeNumber == null) {
                            dub.episodeNumber = latestNumber?.plus(1)
                        }

                        latestNumber = dub.episodeNumber
                        if (latestNumber != dataList[0].toIntOrNull()) return@forEach

                        with(dub.episodeUrl) {
                            when {
                                contains("https://tortuga.wtf/vod/") -> {
                                    M3u8Helper.generateM3u8(
                                        source = dub.playerName,
                                        streamUrl = AshdiExtractor().ParseM3U8(this),
                                        referer = "https://tortuga.wtf/"
                                    )
                                        .forEach(callback)
                                }
                                contains("https://ashdi.vip/vod") -> {
                                    M3u8Helper.generateM3u8(
                                        source = dub.playerName,
                                        streamUrl = AshdiExtractor().ParseM3U8(this),
                                        referer = "https://qeruya.cyou"
                                    )
                                        .forEach(callback)
                                }
                                contains("https://www.udrop.com") -> {
                                    callback.invoke(
                                        ExtractorLink(
                                            dub.playerName,
                                            name = dub.playerName,
                                            this,
                                            "",
                                            0,
                                            isM3u8 = false,
                                        )
                                    )
                                }
                                contains("https://monstro.site/embed/") || contains("https://csst.online/embed/") -> {
                                    csstExtractor().ParseUrl(this).split(",").forEach {
                                        callback.invoke(
                                            ExtractorLink(
                                                dub.playerName,
                                                name = "${dub.playerName.replace("\"", "")} ${it.substringBefore("]").drop(1)}",
                                                it.substringAfter("]"),
                                                "",
                                                0,
                                                isM3u8 = false
                                            )
                                        )
                                    }
                                }
                                contains("https://www.mp4upload.com/") -> {
                                    Mp4Upload().getUrl(this)?.forEach { extlink ->
                                        callback.invoke(
                                            ExtractorLink(
                                                extlink.source,
                                                dub.playerName,
                                                extlink.url,
                                                extlink.referer,
                                                extlink.quality,
                                                extlink.type,
                                                extlink.headers,
                                            )
                                        )
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    private fun decode(input: String): String {
        // Decoded string, thanks to Secozzi
        val hexRegex = Regex("\\\\u([0-9a-fA-F]{4})")
        return hexRegex.replace(input) { matchResult ->
            Integer.parseInt(matchResult.groupValues[1], 16).toChar().toString()
        }
    }

    data class Responses(val success: Boolean?, val response: String?, val message: String?)

    // Thanks to Andro999b
    // https://github.com/Andro999b/movies-telegram-bot/blob/a296c7d4122a25fa70b612e75d741dd55c154640/functions/src/providers/AnitubeUAProvider.ts#L86-L137
    private suspend fun fromPlaylistAjax(url: String): List<Ajax>? {
        val responseGet = app.get(url).parsedSafe<Responses>()

        // Not Ajax, return null
        if (responseGet?.success == false) {
            // Log.d("load-debug", "Not Ajax")
            return null
        }

        val returnEpisodes = mutableListOf<Ajax>()

        val playlist = Jsoup.parse(responseGet?.response!!)
        val audios = mutableListOf<Pair<String, String>>() // (INARI, 0_0, ...)
        val listDubStatus = mutableListOf<Pair<String, String>>() // (СУБТИТРИ, 0_0_0, ...)
        val listPlayers = mutableListOf<Pair<String, String>>() // (ПЛЕЄР МОНСТР, 0_0_0_0, ...)
        playlist.select(".playlists-lists .playlists-items:first-child li").forEach {
            audios.add(Pair(it.text(), it.attr("data-id")))
        }
        // Set listPlayers and listDubStatus
        // If has subs - So players in 3 index
        if (playlist.select(".playlists-lists .playlists-items").count() == 3) {
            // Players
            playlist.select(".playlists-lists .playlists-items:nth-child(3) li").forEach {
                listPlayers.add(Pair(it.text(), it.attr("data-id")))
            }
            // Subs/Dubs index
            playlist.select(".playlists-lists .playlists-items:nth-child(2) li").forEach {
                listDubStatus.add(Pair(it.text(), it.attr("data-id")))
            }
        } else {
            // No subs
            // Players
            playlist.select(".playlists-lists .playlists-items:nth-child(2) li").forEach {
                listPlayers.add(Pair(it.text(), it.attr("data-id")))
            }
        }
        // Parse episodes
        playlist.select(".playlists-videos .playlists-items li").forEach { element ->
            val audioId = element.attr("data-id") // 0_0_0 or 0_0_0_0 if subs
            val episodeId = extractIntFromString(element.text())
            val url = element.attr("data-file")

            var isDub = true
            var audio: String? = null
            var playerName = ""

            // Set this element Dub name
            audios.forEach {
                if (audioId.startsWith(it.second)) {
                    audio = it.first
                }
            }

            if (audioId.count { it == '_' } == 3) {
                listDubStatus.forEach {
                    if (audioId.startsWith(it.second)) {
                        if (it.first == "СУБТИТРИ") {
                            isDub = false
                        }
                    }
                }
            }

            listPlayers.forEach {
                if (audioId.startsWith(it.second)) {
                    playerName = it.first
                }
            }

            returnEpisodes.add(
                Ajax(
                    episodeId,
                    element.text(),
                    Link(
                        isDub,
                        url,
                        audio.toString(),
                        playerName,
                    )
                )
            )
        }

        return returnEpisodes.toList()
    }

    private fun fromVideoContructor(script: Element): List<videoConstructor> {
        val playerScriptRawJson = script.data().substringAfterLast(".init(").substringBefore(");")
        val playerEpisodesRawJson =
            playerScriptRawJson.substringAfter("],").substringBeforeLast(",")
        val playerNamesArray =
            (playerScriptRawJson.substringBefore("],") + "]")
                .dropLast(1)
                .drop(1)
                .replace("\",\"", ",,,")
                .split(",,,")
        // val numberOfEpisodesInt = playerScriptRawJson.substringAfterLast(",").toIntOrNull()

        val jsonEpisodes = tryParseJson<List<List<PlayerJson>>>(playerEpisodesRawJson)!!
        val episodes = mutableListOf<videoConstructor>()

        jsonEpisodes.forEachIndexed { index, episode ->
            val playerName = decode(playerNamesArray[index])
            episode.forEach {
                episodes.add(
                    videoConstructor(
                        playerName,
                        it.name,
                        extractIntFromString(it.name),
                        Jsoup.parse(it.code).select("iframe").attr("src")
                    )
                )
                // Log.d("load-debug", "$playerName ${it.name}")
            }
        }
        return episodes.toList()
    }

    private fun extractIntFromString(string: String): Int? {
        val value = Regex("(\\d+)").findAll(string).lastOrNull() ?: return null
        if (value.value[0].toString() == "0") {
            return value.value.drop(1).toIntOrNull()
        }

        return value.value.toIntOrNull()
    }
}
