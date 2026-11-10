package com.lagradost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SimpsonsTitleParsingTest {
    @Test
    fun `blog material is not valid catalog content`() {
        assertFalse(
            isValidContentUrl(
                "https://simpsonsua.tv/blog/3658-futurama-povertaetsa.html",
            ),
        )
    }

    @Test
    fun `fallback title removes numeric id and html suffix`() {
        assertEquals(
            "Futurama Povertaetsa",
            fallbackTitle("https://simpsonsua.tv/blog/3658-futurama-povertaetsa.html"),
        )
    }

    @Test
    fun `known section title still uses localized mapping`() {
        assertEquals(
            "Футурама",
            fallbackTitle("https://simpsonsua.tv/allfuturama/"),
        )
    }

    @Test
    fun `season number extracted correctly from url`() {
        assertEquals(25, parseSeasonNumber("https://simpsonsua.tv/simpsony/sezon-25/"))
        assertEquals(-1, parseSeasonNumber("https://simpsonsua.tv/simpsony/"))
    }
}
