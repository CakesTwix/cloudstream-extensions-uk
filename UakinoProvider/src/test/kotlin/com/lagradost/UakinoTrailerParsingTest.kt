package com.lagradost

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UakinoTrailerParsingTest {
    @Test
    fun `трейлер береться з окремої вкладки а не з основного плеєра`() {
        val document = Jsoup.parse(
            """
            <div class="tabs">
                <button data-tab="player">UA #1</button>
                <button data-tab="trailer">Трейлер</button>
            </div>
            <div class="tabs_b" data-tab-content="player">
                <iframe id="pre" src="https://ashdi.vip/vod/123"></iframe>
            </div>
            <div class="tabs_b" data-tab-content="trailer">
                <iframe src="https://www.youtube.com/embed/trailer123"></iframe>
            </div>
            """.trimIndent()
        )

        assertEquals(
            "https://www.youtube.com/embed/trailer123",
            extractUakinoTrailer(document),
        )
    }

    @Test
    fun `без вкладки трейлера основний плеєр не повертається`() {
        val document = Jsoup.parse(
            """
            <div class="tabs_b visible">
                <iframe id="pre" src="https://ashdi.vip/vod/123"></iframe>
            </div>
            """.trimIndent()
        )

        assertNull(extractUakinoTrailer(document))
    }

    @Test
    fun `трейлер береться з schema org link itemprop`() {
        val document = Jsoup.parse(
            """
            <div itemscope itemtype="https://schema.org/Movie">
                <link itemprop="video" value="https://ashdi.vip/vod/96531">
                <link itemprop="trailer" value="https://www.youtube.com/embed/VJsPZvs4yrA">
                <ul class="tabs">
                    <li>UA #1</li>
                    <li>Трейлер</li>
                </ul>
                <div class="box full-text visible">
                    <iframe id="pre" src="https://ashdi.vip/vod/96531"></iframe>
                </div>
                <div class="box full-text" id="overroll">
                    <iframe id="pre" src="https://www.youtube.com/embed/VJsPZvs4yrA"></iframe>
                </div>
            </div>
            """.trimIndent()
        )

        assertEquals(
            "https://www.youtube.com/embed/VJsPZvs4yrA",
            extractUakinoTrailer(document),
        )
    }
}
