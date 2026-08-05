package com.lagradost

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BambooTrailerParsingTest {
    @Test
    fun `youtube трейлер знаходиться через вкладку`() {
        val document = Jsoup.parse(
            """
            <div class="player-footer_tabs">
                <a href="#player_1">Відео плеєр Bambooua</a>
                <a href="#player_5">Трейлер Youtube</a>
            </div>
            <div id="player_1"><video><source src="https://video.example/full.m3u8"></video></div>
            <div id="player_5"><video><source src="https://www.youtube.com/watch?v=5XZr0dblM9g"></video></div>
            """.trimIndent()
        )

        assertEquals(
            "https://www.youtube.com/watch?v=5XZr0dblM9g",
            extractBambooTrailer(document),
        )
    }

    @Test
    fun `без трейлера повертається null`() {
        assertNull(extractBambooTrailer(Jsoup.parse("<div class='player-footer_tabs'></div>")))
    }

    @Test
    fun `непідтримуваний Telegram iframe не додається`() {
        val document = Jsoup.parse(
            """
            <div class="player-footer_tabs"><a href="#player_4">Трейлер</a></div>
            <div id="player_4"><iframe src="https://web.telegram.org/k/stream/example"></iframe></div>
            """.trimIndent()
        )

        assertNull(extractBambooTrailer(document))
    }
}
