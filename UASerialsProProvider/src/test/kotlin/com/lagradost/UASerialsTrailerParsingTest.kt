package com.lagradost

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UASerialsTrailerParsingTest {
    @Test
    fun `трейлер знаходиться за посиланням вкладки`() {
        val document = Jsoup.parse(
            """
            <div class="player-tabs">
                <a href="#trailer">Трейлер</a>
            </div>
            <div id="player"><iframe src="https://player.example/episode"></iframe></div>
            <div id="trailer"><iframe src="https://www.youtube.com/embed/uaserials123"></iframe></div>
            """.trimIndent()
        )

        assertEquals(
            "https://www.youtube.com/embed/uaserials123",
            extractUASerialsTrailer(document),
        )
    }

    @Test
    fun `без trailer блоку повертається null`() {
        val document = Jsoup.parse(
            """
            <div class="visible"><iframe src="https://player.example/episode"></iframe></div>
            """.trimIndent()
        )

        assertNull(extractUASerialsTrailer(document))
    }
}
