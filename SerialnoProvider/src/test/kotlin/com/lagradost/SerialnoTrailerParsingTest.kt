package com.lagradost

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SerialnoTrailerParsingTest {
    @Test
    fun `legacy Tortuga file separates HLS URL from inline subtitle`() {
        val parsed = parseSerialnoEpisodeFile(
            "{Clan Kaizoku}https://calypso.tortuga.tw/hls/serials/devil.in.ohio.s01e01.mvo_93098/hls/index.m3u8(subtitle:[Українські]https://tortuga.tw/player/subtitle/93098_ua.vtt)",
        )

        assertEquals("Clan Kaizoku", parsed?.source)
        assertEquals(
            "https://calypso.tortuga.tw/hls/serials/devil.in.ohio.s01e01.mvo_93098/hls/index.m3u8",
            parsed?.streamUrl,
        )
        assertEquals(
            "[Українські]https://tortuga.tw/player/subtitle/93098_ua.vtt",
            parsed?.subtitle,
        )
    }

    @Test
    fun `трейлер береться з video-box відповідно до вкладки`() {
        val document = Jsoup.parse(
            """
            <div class="tabs-sel"><span>Дивитися онлайн</span><span>Трейлер</span></div>
            <div class="tabs-b video-box"><iframe src="https://tortuga.tw/embed/596"></iframe></div>
            <div class="tabs-b video-box"><iframe src="https://tortuga.tw/vod/30697"></iframe></div>
            """.trimIndent()
        )

        assertEquals(
            "https://tortuga.tw/vod/30697",
            extractSerialnoTrailer(document),
        )
    }

    @Test
    fun `відсутня вкладка трейлера не використовує основний плеєр`() {
        val document = Jsoup.parse(
            """
            <div class="tabs-sel"><span>Дивитися онлайн</span></div>
            <div class="tabs-b video-box"><iframe src="https://tortuga.tw/embed/596"></iframe></div>
            """.trimIndent()
        )

        assertNull(extractSerialnoTrailer(document))
    }

    @Test
    fun `сторінка Tortuga трейлера декодується до HLS`() {
        assertEquals(
            "https://calypso.tortuga.tw/hls/trailers/demo/index.m3u8",
            resolveSerialnoTrailerUrl(
                "https://tortuga.tw/vod/30697",
                "<script>playerjs({file:'encoded'});</script>",
                decode = { "https://calypso.tortuga.tw/hls/trailers/demo/index.m3u8" },
            ),
        )
    }
}
