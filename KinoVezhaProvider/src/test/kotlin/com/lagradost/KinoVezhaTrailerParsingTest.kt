package com.lagradost

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KinoVezhaTrailerParsingTest {
    @Test
    fun `мультсеріал визначається як серіал`() {
        assertTrue(isKinoVezhaSeries(listOf("Канада", "Мультсеріал")))
    }

    @Test
    fun `episode file очищується від порожнього суфікса субтитрів`() {
        val parsed = parseKinoVezhaEpisodeFile(
            "{ПлюсПлюс}https://calypso.tortuga.tw/hls/serials/paw.patrol/s01/paw.patrol.s01e01.plusplus.dub_99824/hls/index.m3u8(subtitle:)",
        )

        assertEquals("ПлюсПлюс", parsed?.source)
        assertEquals(
            "https://calypso.tortuga.tw/hls/serials/paw.patrol/s01/paw.patrol.s01e01.plusplus.dub_99824/hls/index.m3u8",
            parsed?.streamUrl,
        )
        assertNull(parsed?.subtitle)
    }

    @Test
    fun `trailer вкладка знаходить окремий iframe`() {
        val document = Jsoup.parse(
            """
            <div class="tabs-block__select--player">
                <span>Плеєр</span><span>Трейлер</span>
            </div>
            <div class="tabs-block__content video-inside"><iframe src="https://tortuga.tw/vod/14550"></iframe></div>
            <div class="tabs-block__content video-inside"><iframe src="https://tortuga.tw/vod/11631"></iframe></div>
            """.trimIndent()
        )

        assertEquals("https://tortuga.tw/vod/11631", extractKinoVezhaTrailer(document))
    }

    @Test
    fun `сторінка Tortuga трейлера перетворюється на HLS`() {
        val html = "<script>playerjs({file:'encoded'});</script>"

        assertEquals(
            "https://calypso.tortuga.tw/hls/trailers/demo/index.m3u8",
            resolveKinoVezhaTrailerUrl(
                "https://tortuga.tw/vod/11631",
                html,
                decode = { "https://calypso.tortuga.tw/hls/trailers/demo/index.m3u8" },
            ),
        )
    }

    @Test
    fun `відсутня trailer вкладка повертає null`() {
        assertNull(extractKinoVezhaTrailer(Jsoup.parse("<div></div>")))
    }
}
