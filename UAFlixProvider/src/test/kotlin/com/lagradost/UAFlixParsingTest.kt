package com.lagradost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UAFlixParsingTest {
    @Test
    fun `episode number comes from episode title`() {
        assertEquals(
            2 to 7,
            parseEpisodeNumbers("2 Сезон", "7 Серія"),
        )
    }

    @Test
    fun `subtitle parser keeps the final URL character and removes a trailing comma`() {
        val parsed = parseUAFlixSubtitle("[Українські (UA)]https://zetvideo.net/player/subtitle/62006_ua.vtt,")

        assertEquals("Українські (UA)", parsed?.language)
        assertEquals("https://zetvideo.net/player/subtitle/62006_ua.vtt", parsed?.url)
    }

    @Test
    fun `invalid subtitle values are ignored`() {
        assertNull(parseUAFlixSubtitle(null))
        assertNull(parseUAFlixSubtitle(""))
        assertNull(parseUAFlixSubtitle("[Українські]"))
        assertNull(parseUAFlixSubtitle("[Українські]not-a-url"))
    }
}
