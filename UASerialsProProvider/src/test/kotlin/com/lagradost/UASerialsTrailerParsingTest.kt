package com.lagradost

import com.lagradost.models.AESPlayerDecodedModel
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

    @Test
    fun `трейлер із зашифрованої вкладки плеєра знаходиться`() {
        val document = Jsoup.parse("<player-control></player-control>")
        val playerTabs = listOf(
            AESPlayerDecodedModel("Плеєр", "https://tortuga.tw/embed/638"),
            AESPlayerDecodedModel("Трейлер", "https://tortuga.tw/vod/31529"),
        )

        assertEquals(
            "https://tortuga.tw/vod/31529",
            extractUASerialsTrailer(document, playerTabs),
        )
    }

    @Test
    fun `сторінка Tortuga трейлера декодується до HLS`() {
        val trailerHtml = """
            <script>
                playerjs({ file: "encoded-trailer" });
            </script>
        """.trimIndent()

        assertEquals(
            "https://calypso.tortuga.tw/hls/trailers/demo/index.m3u8",
            resolveUASerialsTrailerUrl(
                "https://tortuga.tw/vod/31529",
                trailerHtml,
                decode = { "https://calypso.tortuga.tw/hls/trailers/demo/index.m3u8" },
            ),
        )
    }

}
