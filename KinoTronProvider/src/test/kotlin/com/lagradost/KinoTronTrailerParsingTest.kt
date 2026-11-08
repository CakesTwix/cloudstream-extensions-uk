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

    @Test
    fun `isKinoTronComingSoon розпізнає блок анонсу fmessage`() {
        val document = Jsoup.parse(
            """
            <div class="fmessage">
                <span style="font-size:14pt;"><b>Фільм скоро вийде українською, наразі це анонс та трейлер.</b></span>
            </div>
            """.trimIndent()
        )

        assertTrue(isKinoTronComingSoon(document))
    }

    @Test
    fun `isKinoTronComingSoon повертає false коли немає повідомлення про анонс`() {
        val document = Jsoup.parse(
            """
            <div class="full-text">Опис фільму</div>
            """.trimIndent()
        )

        assertFalse(isKinoTronComingSoon(document))
    }

    @Test
    fun `youtube у video-box використовується як трейлер коли немає окремого trailer-box`() {
        val document = Jsoup.parse(
            """
            <div class="video-box">
                <iframe data-src="https://www.youtube.com/embed/0PPX_98KEx0"></iframe>
            </div>
            """.trimIndent()
        )

        assertEquals(
            "https://www.youtube.com/embed/0PPX_98KEx0",
            extractKinoTronTrailer(document),
        )
    }

    @Test
    fun `сторінка анонсу Одіссея успішно витягує трейлер з trailer-box`() {
        val document = Jsoup.parse(
            """
            <div class="fmessage">
                <span style="font-size:14pt;"><b>Фільм скоро вийде українською, наразі це анонс та трейлер.</b></span>
            </div>
            <div class="video-box hidden trailer-box" id="trailer-box" title="Одіссея український трейлер">
                <iframe width="560" height="400" data-src="https://www.youtube.com/embed/0PPX_98KEx0" frameborder="0" allowfullscreen></iframe>
            </div>
            """.trimIndent()
        )

        assertTrue(isKinoTronComingSoon(document))
        assertEquals(
            "https://www.youtube.com/embed/0PPX_98KEx0",
            extractKinoTronTrailer(document),
        )
    }
}
