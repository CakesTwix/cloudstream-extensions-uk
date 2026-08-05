package com.lagradost

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KinoTronTrailerParsingTest {
    @Test
    fun `ashdi serial player identifies a series even when site label is movie`() {
        assertTrue(isKinoTronSeriesPlayerUrl("https://ashdi.vip/serial/6669"))
    }

    @Test
    fun `youtube який продубльований у Player1 не є основним відео`() {
        assertFalse(
            isKinoTronPlayableMainPlayer(
                "https://www.youtube.com/embed/0PPX_98KEx0",
                "https://www.youtube.com/embed/0PPX_98KEx0",
            )
        )
    }

    @Test
    fun `явний trailer-box має пріоритет над основним плеєром`() {
        val document = Jsoup.parse(
            """
            <div class="tabs-sel"><span>Онлайн</span><span>Трейлер</span></div>
            <div class="tabs-b video-box"><iframe data-src="https://player.example/main"></iframe></div>
            <div class="tabs-b video-box"><iframe data-src="https://player.example/second"></iframe></div>
            <div class="video-box hidden trailer-box">
                <iframe data-src="https://www.youtube.com/embed/odQikoUL21I"></iframe>
            </div>
            """.trimIndent()
        )

        assertEquals(
            "https://www.youtube.com/embed/odQikoUL21I",
            extractKinoTronTrailer(document),
        )
    }

    @Test
    fun `відсутній трейлер повертає null`() {
        assertNull(
            extractKinoTronTrailer(
                Jsoup.parse(
                    """
                    <div class="tabs-sel"><span>Онлайн</span></div>
                    <div class="tabs-b video-box"><iframe data-src="https://player.example/main"></iframe></div>
                    """.trimIndent()
                )
            )
        )
    }
}
