package com.lagradost

import org.junit.Assert.assertEquals
import org.junit.Test

class UakinoParsingTest {
    @Test
    fun `player URL keeps a valid HTTPS scheme`() {
        assertEquals("https://video.example/player", normalizeUakinoPlayerUrl("http://video.example/player"))
        assertEquals("https://video.example/player", normalizeUakinoPlayerUrl("//video.example/player"))
    }

    @Test
    fun `episode name keeps commas after the data separator`() {
        val parsed = parseUakinoEpisodeData("https://uakino.best/player,Серія 1, розширена версія")

        assertEquals("https://uakino.best/player", parsed.requestUrl)
        assertEquals("Серія 1, розширена версія", parsed.episodeName)
    }

    @Test
    fun `invalid year falls back to the existing default`() {
        assertEquals(2023, parseUakinoYear("невідомо", 2023))
    }
}
