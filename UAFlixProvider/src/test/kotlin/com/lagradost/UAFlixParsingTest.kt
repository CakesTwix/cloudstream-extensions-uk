package com.lagradost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
