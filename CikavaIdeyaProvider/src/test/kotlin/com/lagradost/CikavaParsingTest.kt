package com.lagradost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CikavaParsingTest {
    @Test
    fun `player data keeps stream and parses a labelled subtitle`() {
        val script = """
            player = new Playerjs({
                file:'https://video.example/index.m3u8',
                subtitle:'[Українська]https://video.example/subtitles/1.vtt'
            });
        """.trimIndent()

        val parsed = parseCikavaPlayerData(script)

        assertEquals("https://video.example/index.m3u8", parsed.streamUrl)
        assertEquals("Українська", parsed.subtitle?.language)
        assertEquals("https://video.example/subtitles/1.vtt", parsed.subtitle?.url)
    }

    @Test
    fun `malformed or empty subtitle is ignored`() {
        assertNull(parseCikavaSubtitle("https://video.example/index.m3u8"))
        assertNull(parseCikavaSubtitle("[Українська]"))
        assertNull(parseCikavaSubtitle("[Українська]not-a-url"))
    }
}
