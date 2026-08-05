package com.lagradost

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CikavaParsingTest {
    @Test
    fun `player data keeps stream and parses a labelled subtitle`() {
        val script = """
            player = new Playerjs({
                file:'https://video.example/index.m3u8',
                subtitle:'[Українська]https://video.example/subtitles/1.vtt'
            });
        """.trimIndent()

        val parsed = parseCikavaPlayerData(script)

        assertEquals("https://video.example/index.m3u8", parsed.streamUrl)
        assertEquals("Українська", parsed.subtitle?.language)
        assertEquals("https://video.example/subtitles/1.vtt", parsed.subtitle?.url)
    }

    @Test
    fun `malformed or empty subtitle is ignored`() {
        assertNull(parseCikavaSubtitle("https://video.example/index.m3u8"))
        assertNull(parseCikavaSubtitle("[Українська]"))
        assertNull(parseCikavaSubtitle("[Українська]not-a-url"))
    }

    @Test
    fun `deleted catalog item is detected by the dedicated quality marker`() {
        val deleted = Jsoup.parse(
            "<article class='th-item'><div class='fquality'>ВИДАЛЕНО</div></article>"
        ).selectFirst(".th-item")!!
        val available = Jsoup.parse(
            "<article class='th-item'><div class='fquality'>HD</div></article>"
        ).selectFirst(".th-item")!!

        assertTrue(isCikavaDeleted(deleted))
        assertFalse(isCikavaDeleted(available))
    }

    @Test
    fun `rights-holder removal notice marks an item as unavailable`() {
        val item = Jsoup.parse(
            """
            <article class="th-item">
                Озвучення ставимо на пауз через малу підтримку глядачів!
                Онлайн видалено на прохання правовласника.
            </article>
            """.trimIndent()
        ).selectFirst(".th-item")!!

        assertTrue(isCikavaDeleted(item))
    }

    @Test
    fun `short rights-holder removal note also marks an item as unavailable`() {
        val item = Jsoup.parse(
            "<article class='th-item'>Видалено на прохання правовласника. Шукайте на інших сайтах.</article>"
        ).selectFirst(".th-item")!!

        assertTrue(isCikavaDeleted(item))
    }

    @Test
    fun `series rights-holder removal note also marks an item as unavailable`() {
        val item = Jsoup.parse(
            "<article class='th-item'>Серіал видалено на прохання правовласника.</article>"
        ).selectFirst(".th-item")!!

        assertTrue(isCikavaDeleted(item))
    }

    @Test
    fun `trailer is selected by tab index and not by the main player`() {
        val document = Jsoup.parse(
            """
            <div class="tabs-sel"><span>Плеєр 1</span><span>Трейлер</span></div>
            <div class="tabs-b video-box"><iframe src="https://ashdi.vip/vod/123"></iframe></div>
            <div class="tabs-b video-box"><iframe src="https://www.youtube.com/embed/hFNad8JtBak"></iframe></div>
            """.trimIndent()
        )

        assertEquals(
            "https://www.youtube.com/embed/hFNad8JtBak",
            extractCikavaTrailer(document),
        )
    }

    @Test
    fun `empty trailer iframe is ignored`() {
        val document = Jsoup.parse(
            """
            <div class="tabs-sel"><span>Плеєр 1</span><span>Трейлер</span></div>
            <div class="tabs-b video-box"><iframe src=""></iframe></div>
            <div class="tabs-b video-box"><iframe src=""></iframe></div>
            """.trimIndent()
        )

        assertNull(extractCikavaTrailer(document))
    }
}
