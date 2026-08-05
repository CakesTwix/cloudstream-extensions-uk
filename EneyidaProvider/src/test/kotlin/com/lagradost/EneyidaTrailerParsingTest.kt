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
}
