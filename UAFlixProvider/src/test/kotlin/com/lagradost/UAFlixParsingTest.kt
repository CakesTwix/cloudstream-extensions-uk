package com.lagradost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jsoup.Jsoup

class UAFlixParsingTest {
    @Test
    fun `episode number comes from episode title`() {
        assertEquals(
            2 to 7,
            parseEpisodeNumbers("2 Сезон", "7 Серія"),
        )
    }

    @Test
    fun `subtitle parser keeps the final URL character and removes a trailing comma`() {
        val parsed = parseUAFlixSubtitle("[Українські (UA)]https://zetvideo.net/player/subtitle/62006_ua.vtt,")

        assertEquals("Українські (UA)", parsed?.language)
        assertEquals("https://zetvideo.net/player/subtitle/62006_ua.vtt", parsed?.url)
    }

    @Test
    fun `invalid subtitle values are ignored`() {
        assertNull(parseUAFlixSubtitle(null))
        assertNull(parseUAFlixSubtitle(""))
        assertNull(parseUAFlixSubtitle("[Українські]"))
        assertNull(parseUAFlixSubtitle("[Українські]not-a-url"))
    }

    @Test
    fun `trailer is read from the dedicated UAFlix button`() {
        val document = Jsoup.parse(
            """
            <div class="video-box"><iframe src="https://zetvideo.net/player/123"></iframe></div>
            <div class="fbtn to-trailer" data-src="https://www.youtube.com/embed/BXGZMnI8rFA">
                Дивитись трейлер
            </div>
            """.trimIndent()
        )

        assertEquals(
            "https://www.youtube.com/embed/BXGZMnI8rFA",
            extractUAFlixTrailer(document),
        )
    }

    @Test
    fun `trailer parser does not use the main player when trailer is absent`() {
        val document = Jsoup.parse(
            """
            <div class="video-box"><iframe src="https://zetvideo.net/player/123"></iframe></div>
            <div class="fbtn">Дивитись онлайн</div>
            """.trimIndent()
        )

        assertNull(extractUAFlixTrailer(document))
    }

    @Test
    fun `coming soon is detected from the premiere span`() {
        val document = Jsoup.parse(
            """
            <div class="rate-yx-share">
                <span style="color:#e85d00">Прем'єра: <srtrong>незабаром</srtrong>
                    <span class="fa fa-question-circle-o"><div>Фільм (серіал) вийде не раніше запланованої дати.</div></span>
                </span>
            </div>
            """.trimIndent()
        )

        assertTrue(isUAFlixComingSoon(document))
    }

    @Test
    fun `premiere button marks unreleased content as coming soon`() {
        val document = Jsoup.parse(
            """
            <div class="subtitle icon-l">
                <h2>Фільм Мертва хватка (2026) дивитись онлайн українською:</h2>
                <button class="btn btn-warning">Прем'єра - незабаром</button>
            </div>
            """.trimIndent()
        )

        assertTrue(isUAFlixComingSoon(document))
    }

    @Test
    fun `premiere button with date marks unreleased content as coming soon`() {
        val document = Jsoup.parse(
            """
            <div class="subtitle icon-l">
                <h2>Фільм Веріті (2026) дивитись онлайн українською:</h2>
                <button class="btn btn-warning">Прем'єра - жовтень 2026</button>
            </div>
            """.trimIndent()
        )

        assertTrue(isUAFlixComingSoon(document))
    }

    @Test
    fun `content with video player is not marked as coming soon even if premiere button exists`() {
        val document = Jsoup.parse(
            """
            <div class="video-box"><iframe src="https://zetvideo.net/player/123"></iframe></div>
            <button class="btn btn-warning">Прем'єра - відбулась</button>
            """.trimIndent()
        )

        assertFalse(isUAFlixComingSoon(document))
    }

    @Test
    fun `released content is not marked as coming soon`() {
        val document = Jsoup.parse(
            """
            <div class="rate-yx-share"><div class="rating-box">100</div></div>
            <div class="subtitle icon-l"><h2>Дивитись онлайн</h2></div>
            """.trimIndent()
        )

        assertFalse(isUAFlixComingSoon(document))
    }

    @Test
    fun `premiere wording without the marker elements is ignored`() {
        val document = Jsoup.parse(
            """
            <div class="fdesc full-text" id="fdesc">Прем'єра відбудеться незабаром після завершення зйомок.</div>
            """.trimIndent()
        )

        assertFalse(isUAFlixComingSoon(document))
    }

    @Test
    fun `title for a serial drops the online-ukrainian seo tail`() {
        val document = Jsoup.parse(
            """
            <div class="fright fx-1">
                <h1 id="ftitle"><span itemprop="name">Гангстерленд / Земля гангстерів</span>  дивитись онлайн українською </h1>
            </div>
            """.trimIndent()
        )

        assertEquals("Гангстерленд / Земля гангстерів", extractUAFlixTitle(document))
    }

    @Test
    fun `title for a film drops the online seo tail from the h1`() {
        val document = Jsoup.parse(
            """
            <div class="fright fx-1">
                <h1 id="ftitle" itemprop="name">Надія дивитись онлайн</h1>
            </div>
            """.trimIndent()
        )

        assertEquals("Надія", extractUAFlixTitle(document))
    }

    @Test
    fun `title seo tail is stripped even without the name span`() {
        val document = Jsoup.parse(
            """
            <div class="fright fx-1">
                <h1 id="ftitle">Чарівний лицар Рейарт дивитись онлайн українською</h1>
            </div>
            """.trimIndent()
        )

        assertEquals("Чарівний лицар Рейарт", extractUAFlixTitle(document))
    }

    @Test
    fun `plain title without a seo tail is unchanged`() {
        val document = Jsoup.parse(
            """
            <div class="fright fx-1"><h1 id="ftitle"><span itemprop="name">Тед-мандрівник і Магічна лампа</span></h1></div>
            """.trimIndent()
        )

        assertEquals("Тед-мандрівник і Магічна лампа", extractUAFlixTitle(document))
    }

    @Test
    fun `seo words inside the name are kept and only the trailing tail is stripped`() {
        val document = Jsoup.parse(
            """
            <div class="fright fx-1">
                <h1 id="ftitle">Як дивитись онлайн українською вдома дивитись онлайн</h1>
            </div>
            """.trimIndent()
        )

        assertEquals("Як дивитись онлайн українською вдома", extractUAFlixTitle(document))
    }

    @Test
    fun `trailing whitespace around the seo tail is removed`() {
        val document = Jsoup.parse(
            """
            <div class="fright fx-1">
                <h1 id="ftitle">Чорна конюшина 1, 2 сезон дивитись онлайн українською </h1>
            </div>
            """.trimIndent()
        )

        assertEquals("Чорна конюшина 1, 2 сезон", extractUAFlixTitle(document))
    }

    @Test
    fun `the disytysya variant of the seo tail is also stripped from the end`() {
        val document = Jsoup.parse(
            """
            <div class="fright fx-1">
                <h1 id="ftitle">Повість про Генджі дивитися онлайн українською</h1>
            </div>
            """.trimIndent()
        )

        assertEquals("Повість про Генджі", extractUAFlixTitle(document))
    }

    @Test
    fun `a name ending with online but without the verb keeps the word`() {
        val document = Jsoup.parse(
            """
            <div class="fright fx-1">
                <h1 id="ftitle">Безкоштовно онлайн</h1>
            </div>
            """.trimIndent()
        )

        assertEquals("Безкоштовно онлайн", extractUAFlixTitle(document))
    }

    @Test
    fun `section card title prefers the ukrainian vi-title over the poster alt`() {
        val card = Jsoup.parse(
            """
            <div class="video-item with-mask new-item">
                <div class="vi-in">
                    <a class="vi-img img-resp-h" href="https://uafix.net/films/pavychok4/" alt="Людина-павук: Абсолютно новий день / Людина павук 4 / Spider-Man: Brand New Day">
                        <img src="/uploads/posts/2026-07/1784728540_1.jpg" alt="Людина-павук: Абсолютно новий день / Людина павук 4 / Spider-Man: Brand New Day" />
                        <div class="vi-desc">
                            <div class="vi-title">Людина-павук: Абсолютно новий день / Людина павук 4</div>
                        </div>
                    </a>
                </div>
            </div>
            """.trimIndent()
        ).selectFirst(".video-item")!!

        assertEquals(
            "Людина-павук: Абсолютно новий день / Людина павук 4",
            extractUAFlixCardTitle(card)
        )
    }

    @Test
    fun `section card title keeps ukrainian apostrophes from the vi-title`() {
        val card = Jsoup.parse(
            """
            <div class="video-item with-mask new-item">
                <div class="vi-in">
                    <a class="vi-img img-resp-h" href="https://uafix.net/serials/misija-simji-jozakura/" alt="Місія сімї Йозакура / Mission: Yozakura Family / Yozakura-san Chi no Daisakusen">
                        <img src="/uploads/posts/2024-05/1716188248_misija-simji-jozakura.png" alt="Місія сімї Йозакура / Mission: Yozakura Family / Yozakura-san Chi no Daisakusen" />
                        <div class="vi-desc">
                            <div class="vi-title">Місія сім'ї Йозакура</div>
                        </div>
                    </a>
                </div>
            </div>
            """.trimIndent()
        ).selectFirst(".video-item")!!

        assertEquals("Місія сім'ї Йозакура", extractUAFlixCardTitle(card))
    }

    @Test
    fun `search result without vi-title falls back to the poster alt`() {
        val card = Jsoup.parse(
            """
            <a class="sres-wrap clearfix" href="https://uafix.net/serials/amerikanska-istorija-zhahu/">
                <div class="sres-img"><img src="/uploads/posts/2026-09/1790672219_2733b125d07cc7c00988891db166c30e.jpeg" alt="Американська історія жаху / American Horror Story" /></div>
                <div class="sres-text"><h2>Американська історія жаху / American Horror Story</h2></div>
            </a>
            """.trimIndent()
        ).selectFirst(".sres-wrap")!!

        assertEquals(
            "Американська історія жаху / American Horror Story",
            extractUAFlixCardTitle(card)
        )
    }
}
