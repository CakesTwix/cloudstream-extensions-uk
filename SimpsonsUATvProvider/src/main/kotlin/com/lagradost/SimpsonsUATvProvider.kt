package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.nio.charset.StandardCharsets

class SimpsonsUATvProvider : MainAPI() {

    override var mainUrl = "https://simpsonsua.tv"
    private val UA = "Mozilla/5.0 (Linux; Android 15; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.7778.215 Mobile Safari/537.36"
    private fun headers(referer: String = mainUrl) = mapOf("User-Agent" to UA, "Referer" to referer)
    override var name = "SimpsonsUA"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.TvSeries
    )

    private fun capitalizeWord(str: String): String {
        return str.split(" ").joinToString(" ") { word ->
            if (word.isNotEmpty()) word.substring(0, 1).uppercase() + word.substring(1)
            else ""
        }
    }

    private val titleMap = mapOf(
        "simpsony"             to "Сімпсони",
        "allfuturama"          to "Футурама",
        "family-guy"           to "Гріфіни",
        "pivdennyi-park"       to "Південний Парк",
        "riksanchez"           to "Рік та Морті",
        "solar-opposites"      to "Сонячні протилежності",
        "rozcharuvannya"       to "Розчарування",
        "duncanville"          to "Дунканвілл",
        "nevkolupnyi"          to "Невразливий",
        "central-park"         to "Центральний Парк",
        "sponge"               to "Губка Боб Квадратні Штани",
        "american-dad"         to "Американський тато",
        "clevelandshow"        to "Шоу Клівленда",
        "brickleberry"         to "Бріклбері",
        "pd-paradise"          to "Поліція Парадайз",
        "polus"                to "Полюс",
        "bojack"               to "Кінь BoДжек",
        "tuca-and-bertie"      to "Тука і Bertie",
        "big-mouth"            to "Великий рот",
        "gravity-falls"        to "Ґравіті Фолз",
        "amfibiya"             to "Амфібія",
        "owl-house"            to "Совиний Дім",
        "hotel-hazbin"         to "Готель Хазбін",
        "pekelniy-bos"         to "Пекельний бос",
        "gilda"                to "Гільда",
        "final-space"          to "Космічний рубіж",
        "adventure-time"       to "Час пригод",
        "star-proty-syl-zla"   to "Зоряна принцеса проти сил зла",
        "opivnichne-evangelie" to "Опівнічне Євангеліє",
        "infinity-train"       to "Нескінченний поїзд",
        "my-little-pony"       to "My Little Pony",
        "maylo-merfi"          to "Закон Майла Мерфі",
        "fineas-ferb"          to "Фінеас і Ферб",
        "rockos-modern-life"   to "Сучасне рок-життя Рокко",
        "invader-zim"          to "Загарвник Зім"
    )

    private val sectionNameMap = mapOf(
        "inshe"                   to "Цікавинки",
        "dobirky"                 to "Добірки",
        "halloween"               to "Гелловін",
        "rizdvo"                  to "Різдво",
        "majbutnye"               to "Майбутнє",
        "main-simpsons-episodes"  to "Головні серії",
        "lito"                    to "Літо",
        "pereyizd"                to "Переїзд",
        "podoroz"                 to "Подорожі",
        "shkola"                  to "Школа",
        "love"                    to "Кохання",
        "lgbt"                    to "ЛГБТ",
        "patrik"                  to "Патрік",
        "simpsony-u-kino"         to "У кіно",
        "tracey-ullman-show"      to "Tracey Ullman Show"
    )

    private val specialSlugs = setOf(
        "inshe", "dobirky", "simpsony-u-kino",
        "halloween", "rizdvo", "majbutnye", "main-simpsons-episodes",
        "lito", "pereyizd", "podoroz", "shkola", "love", "lgbt", "patrik",
        "tracey-ullman-show"
    )

    private val ignoredUrlPatterns = listOf(
        "/multserialy-ukrainskoyu/",
        "/terms.html",
        "/subscribe.html",
        "/index.php",
        "do=login",
        "t.me/",
        "youtube.com",
        "tiktok.com",
        "x.com/",
        "franecki.net",
        "franeski.net",
        "javascript:"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Останні оновлення",
        "$mainUrl/multserialy-ukrainskoyu/" to "Усі мультserialи"
    )

    private fun getTitleFromComment(element: Element): String? {
        var prev = element.previousSibling()
        while (prev != null) {
            if (prev is Comment) return prev.data?.trim()
            if (prev is Element) break
            prev = prev.previousSibling()
        }
        return null
    }

    private fun isValidContentUrl(href: String): Boolean {
        if (!href.startsWith("http")) return false
        return ignoredUrlPatterns.none { href.contains(it) }
    }

    private fun urlSlug(url: String) = url.removeSuffix("/").substringAfterLast("/")

    private fun parseSeasonNumber(url: String): Int {
        return Regex("""sezon-(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    private fun parseEpisodeNumber(url: String, fallback: Int): Int {
        return Regex("""(\d+)-seriya""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: fallback
    }

    private fun sectionTitle(url: String, fallbackDoc: Document? = null): String {
        val slug = urlSlug(url)
        sectionNameMap[slug]?.let { return it }
        fallbackDoc?.selectFirst(".cat-nazva h1, h1")?.text()
            ?.replace("дивитися онлайн українською мовою", "")
            ?.replace("дивитися онлайн українською", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return capitalizeWord(slug.replace("-", " "))
    }

    private fun extractImageUrl(el: Element?): String? {
        val img = el?.selectFirst("img") ?: return null
        val rawUrl = img.attr("data-src").takeIf { it.isNotBlank() }
            ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() }
            ?: img.attr("src")
        if (rawUrl.isNullOrBlank()) return null
        // Відносний шлях /photos/... — fixUrl може не додати домен, робимо вручну
        return when {
            rawUrl.startsWith("http") -> rawUrl
            rawUrl.startsWith("//")   -> "https:$rawUrl"
            rawUrl.startsWith("/")    -> "$mainUrl$rawUrl"
            else                      -> fixUrl(rawUrl)
        }
    }

    // Проксі для вертикальних постерів (серіали, каталоги, пошук)
    private fun convertToPortraitProxy(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return try {
        val encodedUrl = java.net.URLEncoder.encode(url, StandardCharsets.UTF_8.name())
        // fit=fill — розтягує постер на весь фон 400x600 і повністю знищує смужки зверху/знизу
        // output=webp — оптимізує вагу картинок для плавності додатка
        "https://images.weserv.nl/?url=$encodedUrl&w=400&h=600&fit=fill&output=webp&q=80"
    } catch (e: Exception) {
        url
    }
}

    // Максимально оптимізований горизонтальний формат для серій (16:9, q=75 для економії трафіку та адаптивності)
    private fun convertToLandscapeProxy(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return try {
        val encodedUrl = java.net.URLEncoder.encode(url, StandardCharsets.UTF_8.name())
        // fpy=0.12 — зміщує кадр ближче до верху (налаштовуйте під себе від 0.0 до 0.2)
        "https://images.weserv.nl/?url=$encodedUrl&w=320&h=180&fit=crop&a=focal&fpx=0.5&fpy=0.20&output=webp&q=75"
    } catch (e: Exception) {
        url
    }
}


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val homePageLists = mutableListOf<HomePageList>()

        if (request.data == "$mainUrl/") {
            if (page == 1) {
                try {
                    val doc = app.get(mainUrl, headers = headers()).document
                    val updates = doc.select("div.ep_slider div.movie_item").take(25).mapNotNull { el ->
                        val href = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                        val posterUrl = extractImageUrl(el)
                        val title = getTitleFromComment(el) ?: "Нова серія"
                        newAnimeSearchResponse(title, href, TvType.Cartoon) {
                            this.posterUrl = convertToPortraitProxy(posterUrl)
                            this.posterHeaders = mapOf("Referer" to mainUrl)
                        }
                    }
                    if (updates.isNotEmpty())
                        homePageLists.add(HomePageList("Останні оновлення серій", updates))
                } catch (e: Exception) { }
            }
            return if (homePageLists.isNotEmpty())
                newHomePageResponse(homePageLists, hasNext = false) else null
        }

        var hasNextPage = false
        try {
            val catalogUrl = if (page == 1) request.data else "${request.data}page/$page/"
            val doc = app.get(catalogUrl, headers = headers()).document
            val items = doc.select("div.movie_item").take(40).mapNotNull { el ->
                val href = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val posterUrl = extractImageUrl(el)
                val slug = urlSlug(href)
                val title = titleMap[slug] ?: capitalizeWord(slug.replace("-", " "))
                newAnimeSearchResponse(title, href, TvType.Cartoon) {
                    this.posterUrl = convertToPortraitProxy(posterUrl)
                    this.posterHeaders = mapOf("Referer" to mainUrl)
                }
            }
            if (items.isNotEmpty()) {
                homePageLists.add(HomePageList("Список мультсеріалів", items))
                hasNextPage = true
            }
        } catch (e: Exception) { hasNextPage = false }

        return if (homePageLists.isNotEmpty())
            newHomePageResponse(homePageLists, hasNext = hasNextPage) else null
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val document = app.get("$mainUrl/?s=$encodedQuery", headers = headers()).document
        return document.select("div.movie_item").mapNotNull { el ->
            val href = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val posterUrl = extractImageUrl(el)
            var title = getTitleFromComment(el)
            if (title.isNullOrBlank()) {
                val slug = urlSlug(href)
                title = titleMap[slug] ?: capitalizeWord(slug.replace("-", " "))
            }
            newAnimeSearchResponse(title, href, TvType.Cartoon) {
                this.posterUrl = convertToPortraitProxy(posterUrl)
                this.posterHeaders = mapOf("Referer" to mainUrl)
            }
        }
    }

    private suspend fun collectFromSpecialSection(
        sectionUrl: String,
        sectionLabel: String,
        seasonNum: Int,
        into: MutableList<Episode>
    ) {
        val doc = try { app.get(sectionUrl, headers = headers()).document } catch (e: Exception) { return }
        val episodeCards = doc.select("#dle-content .movie_item")

        if (episodeCards.any { it.selectFirst("a")?.attr("href")?.contains("-seriya") == true }) {
            episodeCards.forEach { card ->
                val a = card.selectFirst("a") ?: return@forEach
                val epUrl = a.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                if (parseSeasonNumber(epUrl) > 0 && !epUrl.contains("-seriya")) return@forEach
                
                val epNum = parseEpisodeNumber(epUrl, into.count { it.season == seasonNum } + 1)
                val rawName = a.selectFirst(".descr.nazva")?.text()?.trim()
                    ?: a.selectFirst(".title, h2")?.text()?.trim()
                    ?: "Серія $epNum"
                val epDesc = a.selectFirst(".descr:not(.nazva)")?.text()?.trim()
                val epPoster = extractImageUrl(card)
                into.add(newEpisode(epUrl) {
                    this.name        = "[$sectionLabel] $rawName"
                    this.season      = seasonNum
                    this.episode     = epNum
                    this.description = epDesc
                    this.posterUrl   = convertToLandscapeProxy(epPoster)
                })
            }
        } else {
            data class SectItem(val href: String, val cardPoster: String?)
            val sectItems = doc.select("#dle-content .movie_item a")
                .mapNotNull { a ->
                    val href = a.attr("href").takeIf { isValidContentUrl(it) }
                        ?: return@mapNotNull null
                    val cardPoster = extractImageUrl(a.parent())
                    SectItem(href, cardPoster)
                }
                .distinctBy { it.href }

            sectItems.forEach { item ->
                if (item.href.endsWith(".html")) {
                    try {
                        val itemDoc = app.get(item.href, headers = headers()).document
                        val rawName = itemDoc.selectFirst(".poster h2, h1")?.text()?.trim()
                            ?: urlSlug(item.href).replace("-", " ")
                        val itemPoster = item.cardPoster
                            ?: itemDoc.selectFirst(".poster img, div.story img")?.let { extractImageUrl(it.parent()) }
                        val itemDesc = itemDoc.selectFirst(".fullstory, .sez-opys")?.text()?.trim()
                        into.add(newEpisode(item.href) {
                            this.name        = "[$sectionLabel] $rawName"
                            this.season      = seasonNum
                            this.episode     = into.count { it.season == seasonNum } + 1
                            this.description = itemDesc
                            this.posterUrl   = convertToLandscapeProxy(itemPoster)
                        })
                    } catch (e: Exception) { }
                } else {
                    val subLabel = sectionTitle(item.href)
                    collectFromSpecialSection(item.href, subLabel, seasonNum, into)
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers()).document

        val title = document.selectFirst(".poster h2, .cat-nazva h1, h1")?.text()
            ?.replace(Regex("дивитися онлайн.*", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?: document.title()
                .replace(Regex("дивитися онлайн.*", RegexOption.IGNORE_CASE), "")
                .trim()

        val mainImgEl = document.selectFirst(".movie_item, div.story, .poster")
        val poster = extractImageUrl(mainImgEl)
        val description = document.selectFirst(".sez-opys, .fullstory, div.story")?.text()?.trim()

        val episodes = mutableListOf<Episode>()
        val directCards = document.select("#dle-content .movie_item")
        
        val isSeasonPage = parseSeasonNumber(url) > 0 && directCards.any { card -> 
            card.selectFirst("a")?.attr("href")?.contains("-seriya") == true 
        }

        if (isSeasonPage) {
            val seasonNum = parseSeasonNumber(url).coerceAtLeast(1)
            var epCounter = 1
            directCards.forEach { card ->
                val a = card.selectFirst("a") ?: return@forEach
                val epUrl = a.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                if (parseSeasonNumber(epUrl) > 0 && !epUrl.contains("-seriya")) return@forEach
                
                val epNum = parseEpisodeNumber(epUrl, epCounter)
                if (epNum == epCounter) epCounter++
                
                val epName = a.selectFirst(".descr.nazva")?.text()?.trim()
                    ?: a.selectFirst(".title, h2")?.text()?.trim()
                    ?: "Серія $epNum"
                val epDesc = a.selectFirst(".descr:not(.nazva)")?.text()?.trim()
                val epPoster = extractImageUrl(card)
                
                episodes.add(newEpisode(epUrl) {
                    this.name        = epName
                    this.season      = seasonNum
                    this.episode     = epNum
                    this.description = epDesc
                    this.posterUrl   = convertToLandscapeProxy(epPoster)
                })
            }
            return newAnimeLoadResponse(title, url, TvType.TvSeries) {
                this.posterUrl = convertToPortraitProxy(poster)
                this.plot = description
                addEpisodes(DubStatus.Dubbed, episodes)
            }
        }

        data class SubItem(val href: String, val cardPoster: String?)
        val subItems = document.select("#dle-content .movie_item a")
            .mapNotNull { a ->
                val href = a.attr("href").takeIf { isValidContentUrl(it) }
                    ?: return@mapNotNull null
                val cardPoster = extractImageUrl(a.parent())
                SubItem(href, cardPoster)
            }
            .distinctBy { it.href }

        if (subItems.isNotEmpty()) {
            var specialSeasonCounter = 101
            val seasonItems = subItems
                .filter { parseSeasonNumber(it.href) > 0 }
                .sortedBy { parseSeasonNumber(it.href) }

            seasonItems.forEach { item ->
                val seasonNum = parseSeasonNumber(item.href)
                try {
                    val seasonDoc = app.get(item.href, headers = headers()).document
                    val cards = seasonDoc.select("#dle-content .movie_item")
                    var epCounter = 1
                    cards.forEach { card ->
                        val a = card.selectFirst("a") ?: return@forEach
                        val epUrl = a.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                        if (parseSeasonNumber(epUrl) > 0 && !epUrl.contains("-seriya")) return@forEach
                        
                        val epNum = parseEpisodeNumber(epUrl, epCounter)
                        if (epNum == epCounter) epCounter++
                        
                        val epName = a.selectFirst(".descr.nazva")?.text()?.trim()
                            ?: a.selectFirst(".title, h2")?.text()?.trim()
                            ?: "Серія $epNum"
                        val epDesc = a.selectFirst(".descr:not(.nazva)")?.text()?.trim()
                        val epPoster = extractImageUrl(card)
                        
                        episodes.add(newEpisode(epUrl) {
                            this.name        = epName
                            this.season      = seasonNum
                            this.episode     = epNum
                            this.description = epDesc
                            this.posterUrl   = convertToLandscapeProxy(epPoster)
                        })
                    }
                } catch (e: Exception) { }
            }

            val directMovies = subItems.filter {
                it.href.endsWith(".html") && parseSeasonNumber(it.href) < 0
            }
            if (directMovies.isNotEmpty()) {
                val movieSeasonNum = specialSeasonCounter++
                directMovies.forEach { item ->
                    try {
                        val movieDoc = app.get(item.href, headers = headers()).document
                        val movieTitle = movieDoc.selectFirst(".poster h2, h1")?.text()?.trim()
                            ?: urlSlug(item.href).replace("-", " ")
                        val moviePoster = item.cardPoster
                            ?: movieDoc.selectFirst(".poster img, div.story img")?.let { extractImageUrl(it.parent()) }
                        val movieDesc = movieDoc.selectFirst(".fullstory, .sez-opys")?.text()?.trim()
                        episodes.add(newEpisode(item.href) {
                            this.name        = movieTitle
                            this.season      = movieSeasonNum
                            this.episode     = episodes.count { it.season == movieSeasonNum } + 1
                            this.description = movieDesc
                            this.posterUrl   = convertToLandscapeProxy(moviePoster)
                        })
                    } catch (e: Exception) { }
                }
            }

            val specialItems = subItems.filter { item ->
                val slug = urlSlug(item.href)
                specialSlugs.contains(slug) ||
                    (parseSeasonNumber(item.href) < 0 && !item.href.endsWith(".html"))
            }

            specialItems.forEach { item ->
                val sectLabel = sectionTitle(item.href)
                val sectSeasonNum = specialSeasonCounter++
                collectFromSpecialSection(item.href, sectLabel, sectSeasonNum, episodes)
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) {
                this.name    = title
                this.season  = 1
                this.episode = 1
                this.posterUrl = convertToLandscapeProxy(poster)
            })
        }

        return newAnimeLoadResponse(title, url, TvType.TvSeries) {
            this.posterUrl = convertToPortraitProxy(poster)
            this.plot      = description
            addEpisodes(DubStatus.Dubbed, episodes.distinctBy { it.data })
        }
    }

    // https://codeberg.org/CakesTwix/cloudstream-extensions-uk/commit/9636555500beebc453b0facec91a73c16f083665
    // Tortuga XOR декодер (алгоритм з tor.core.min.js: сіль = перший байт, далі XOR з (salt + 7*i + 13) % 256)
    private fun tortugaDecode(encoded: String): String? {
        if (encoded.isBlank()) return null
        return try {
            val clean = encoded.trimEnd('=')
            val decoded = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
            if (decoded.isEmpty()) return null
            val salt = decoded[0].toInt() and 0xFF
            val result = ByteArray(decoded.size - 1)
            for (i in 1 until decoded.size) {
                val key = (salt + 7 * (i - 1) + 13) % 256
                result[i - 1] = ((decoded[i].toInt() and 0xFF) xor key).toByte()
            }
            String(result, Charsets.UTF_8).takeIf { it.contains("http") || it.contains(".m3u8") }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, headers = headers()).text
        var found = false

        // ── Існуючий блок: ashdi та інші плеєри з file: "...m3u8..." ──
        val iframeRegex = Regex("<iframe[^>]+data-player=\"([^\"]+)\"[^>]+src=\"([^\"]+)\"")

        iframeRegex.findAll(html).forEach { match ->
            val playerName = match.groupValues[1]
            // Tortuga обробляється окремим блоком
            if (playerName.equals("tortuga", ignoreCase = true)) return@forEach
            var iframeUrl  = match.groupValues[2]
            if (iframeUrl.startsWith("//")) iframeUrl = "https:$iframeUrl"

            try {
                val playerHtml = app.get(
                    iframeUrl,
                    headers = headers(mainUrl)
                ).text

                val fileRaw = Regex("""file\s*:\s*["']([^"']{20,})["']""")
                    .find(playerHtml)?.groupValues?.get(1)

                val m3u8Url: String? = when {
                    fileRaw.isNullOrBlank() -> null
                    fileRaw.contains(".m3u8") -> fileRaw
                    else -> tortugaDecode(fileRaw)
                }

                // playerName з data-player вже містить назву озвучки (ozvuchka-strugachka і т.д.)
                if (!m3u8Url.isNullOrBlank()) {
                    M3u8Helper.generateM3u8(
                        source    = if (playerName.equals("ashdi", ignoreCase = true))
                            "$name ($playerName)"
                        else
                            "$name ($playerName [ashdi])",
                        streamUrl = m3u8Url,
                        referer   = iframeUrl
                    ).dropLast(1).forEach(callback)
                    found = true
                }
            } catch (e: Exception) { }
        }

        // ── Новий блок: Tortuga плеєр ──
        // Шукаємо iframe з src що містить "tortuga.tw" або data-player="tortuga"
        val tortugaIframeRegex = Regex(
            "<iframe[^>]+(?:data-player=\"tortuga\"|src=\"[^\"]*tortuga\\.tw[^\"]*\")[^>]*(?:src=\"([^\"]+)\"[^>]*)?>",
            RegexOption.IGNORE_CASE
        )
        // Також покриваємо випадок коли src йде перед data-player
        val tortugaSrcRegex = Regex(
            """<iframe[^>]+src="(https?:(?://)?[^"]*tortuga\.tw[^"]*)"[^>]*>""",
            RegexOption.IGNORE_CASE
        )

        // Map<url, data-player label>
        val tortugaUrls = mutableMapOf<String, String>()
        // Витягуємо data-player разом з src для Tortuga iframe
        val tortugaFullRegex = Regex(
            """<iframe[^>]+data-player="([^"]+)"[^>]+src="([^"]*tortuga\.tw[^"]*)"[^>]*>""",
            RegexOption.IGNORE_CASE
        )
        tortugaFullRegex.findAll(html).forEach { m ->
            val dpLabel = m.groupValues[1]
            val src = m.groupValues[2].let { if (it.startsWith("//")) "https:$it" else it }
            tortugaUrls[src] = dpLabel
        }
        // Fallback: якщо src іде перед data-player або data-player="tortuga"
        tortugaIframeRegex.findAll(html).forEach { m ->
            val src = m.groupValues[1].takeIf { it.isNotBlank() } ?: return@forEach
            val url = if (src.startsWith("//")) "https:$src" else src
            tortugaUrls.putIfAbsent(url, "tortuga")
        }
        tortugaSrcRegex.findAll(html).forEach { m ->
            val src = m.groupValues[1]
            val url = if (src.startsWith("//")) "https:$src" else src
            tortugaUrls.putIfAbsent(url, "tortuga")
        }

        for ((tortugaUrl, tortugaLabel) in tortugaUrls) {
            try {
                val tortugaHtml = app.get(
                    tortugaUrl,
                    headers = headers(mainUrl)
                ).text

                val fileParam = Regex("""file\s*:\s*["']([^"']{20,})["']""")
                    .find(tortugaHtml)?.groupValues?.get(1)
                    ?: continue

                val sourceName = if (tortugaLabel.equals("tortuga", ignoreCase = true))
                    "$name (Tortuga)"
                else
                    "$name ($tortugaLabel [Tortuga])"

                val m3u8Url: String? = when {
                    // Вже готовий m3u8 — без шифрування
                    fileParam.contains(".m3u8") -> fileParam
                    // XOR декодування (той самий алгоритм що в UASerialsProvider)
                    else -> tortugaDecode(fileParam)
                }

                if (!m3u8Url.isNullOrBlank()) {
                    M3u8Helper.generateM3u8(
                        source    = sourceName,
                        streamUrl = m3u8Url,
                        referer   = tortugaUrl
                    ).dropLast(1).forEach(callback)
                    found = true
                }
            } catch (e: Exception) { }
        }

        return found
    }
}