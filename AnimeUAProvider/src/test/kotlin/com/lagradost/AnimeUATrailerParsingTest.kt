package com.lagradost

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnimeUATrailerParsingTest {
    @Test
    fun `трейлер вибирається за вкладкою, а не за першим Ashdi iframe`() {
        val document = Jsoup.parse(
            """
            <div class="pmovie__player">
                <div class="pmovie__player-controls">
                    <div class="tabs-block__select">
                        <span>Дивитися онлайн</span><span>Трейлер</span>
                    </div>
                </div>
                <div class="tabs-block__content video-inside"><iframe data-src="https://ashdi.vip/serial/6887"></iframe></div>
                <div class="tabs-block__content video-inside"><iframe data-src="https://www.youtube.com/embed/lYmIut_t8Bs"></iframe></div>
            </div>
            """.trimIndent()
        )

        assertEquals(
            "https://www.youtube.com/embed/lYmIut_t8Bs",
            extractAnimeUATrailer(document),
        )
    }

    @Test
    fun `відсутня вкладка трейлера повертає null`() {
        assertNull(
            extractAnimeUATrailer(
                Jsoup.parse(
                    """
                    <div class="pmovie__player-controls"><div class="tabs-block__select"><span>Дивитися онлайн</span></div></div>
                    <div class="tabs-block__content video-inside"><iframe data-src="https://ashdi.vip/serial/6887"></iframe></div>
                    """.trimIndent()
                )
            )
        )
    }
}
