package com.lagradost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SimpsonsTitleParsingTest {
    private val provider = SimpsonsUATvProvider()

    @Test
    fun `blog material is not valid catalog content`() {
        assertFalse(
            provider.isValidContentUrl(
                "https://simpsonsua.tv/blog/3658-futurama-povertaetsa.html",
            ),
        )
    }

    @Test
    fun `fallback title removes numeric id and html suffix`() {
        assertEquals(
            "Futurama Povertaetsa",
            provider.fallbackTitle("https://simpsonsua.tv/blog/3658-futurama-povertaetsa.html"),
        )
    }

    @Test
    fun `known section title still uses localized mapping`() {
        assertEquals(
            "Футурама",
            provider.fallbackTitle("https://simpsonsua.tv/allfuturama/"),
        )
    }
}
