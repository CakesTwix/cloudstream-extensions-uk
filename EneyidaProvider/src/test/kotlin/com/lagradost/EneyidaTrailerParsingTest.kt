package com.lagradost

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EneyidaTrailerParsingTest {
    @Test
    fun `iframe з trailer_place є трейлером`() {
        val document = Jsoup.parse(
            """
            <div id="trailer_place">
                <iframe src="https://www.youtube.com/embed/eneyida123"></iframe>
            </div>
            """.trimIndent()
        )

        assertEquals(
            "https://www.youtube.com/embed/eneyida123",
            extractEneyidaTrailer(document),
        )
    }

    @Test
    fun `відсутній src повертає null`() {
        val document = Jsoup.parse("<div id=\"trailer_place\"><iframe></iframe></div>")

        assertNull(extractEneyidaTrailer(document))
    }

    @Test
    fun `hdvbua player file is extracted as a direct trailer stream`() {
        val script = """
            var player = new Playerjs({
                file: "https://s30.hdvbua.pro/media2/hls/new/amistad.1997_47703/hls/index.m3u8",
                poster: "https://example.com/poster.jpg"
            });
        """.trimIndent()

        assertEquals(
            "https://s30.hdvbua.pro/media2/hls/new/amistad.1997_47703/hls/index.m3u8",
            parseEneyidaTrailerPlayerUrl(script),
        )
    }

    @Test
    fun secondHdvbuaTrailerExampleIsExtractedAsADirectStream() {
        val script = """
            var player = new Playerjs({
                file: "https://s30.hdvbua.pro/media3/hls/trailers/lincoln.2012_10076/hls/index.m3u8"
            });
        """.trimIndent()

        assertEquals(
            "https://s30.hdvbua.pro/media3/hls/trailers/lincoln.2012_10076/hls/index.m3u8",
            parseEneyidaTrailerPlayerUrl(script),
        )
    }

    @Test
    fun `structured player file is not treated as a direct trailer stream`() {
        assertNull(parseEneyidaTrailerPlayerUrl("player = new Playerjs({file: '[{}]'});"))
    }
}
