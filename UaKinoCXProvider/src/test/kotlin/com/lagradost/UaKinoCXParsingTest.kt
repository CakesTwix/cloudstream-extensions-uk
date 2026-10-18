package com.lagradost

import com.lagradost.models.PlayerJson
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UaKinoCXParsingTest {

    @Test
    fun `parseUaKinoCXSubtitle parses bracketed language and URL`() {
        val parsed = parseUaKinoCXSubtitle("[Українські (UA)]https://s11.hdvbua.pro/media/subtitle.vtt")

        assertEquals("Українські (UA)", parsed?.language)
        assertEquals("https://s11.hdvbua.pro/media/subtitle.vtt", parsed?.url)
    }

    @Test
    fun `parseUaKinoCXSubtitle handles trailing comma and direct url`() {
        val parsed = parseUaKinoCXSubtitle("[Українські]https://example.com/sub.vtt,")
        assertEquals("Українські", parsed?.language)
        assertEquals("https://example.com/sub.vtt", parsed?.url)

        val direct = parseUaKinoCXSubtitle("https://example.com/sub.vtt")
        assertEquals("Українські", direct?.language)
        assertEquals("https://example.com/sub.vtt", direct?.url)

        assertNull(parseUaKinoCXSubtitle(null))
        assertNull(parseUaKinoCXSubtitle(""))
    }

    @Test
    fun `extractUaKinoCXTrailer extracts trailer from trailer block`() {
        val document = Jsoup.parse(
            """
            <div class="page__player">
                <div class="tabs-block__content video-inside video-responsive d-none">
                    <iframe data-src="https://hdvbua.pro/embed/1012162/e623cb5a2"></iframe>
                </div>
                <div class="tabs-block__content video-inside video-responsive d-none" id="trailer-block">
                    <iframe data-src="https://www.youtube.com/embed/Mq_D3IMLyEE"></iframe>
                </div>
            </div>
            """.trimIndent()
        )

        assertEquals(
            "https://www.youtube.com/embed/Mq_D3IMLyEE",
            extractUaKinoCXTrailer(document),
        )
    }

    @Test
    fun `extractUaKinoCXPlayerUrls extracts player iframes excluding trailer`() {
        val document = Jsoup.parse(
            """
            <div class="page__player">
                <div class="tabs-block__content video-inside video-responsive d-none">
                    <iframe data-src="https://hdvbua.pro/embed/1012162/e623cb5a2"></iframe>
                </div>
                <div class="tabs-block__content video-inside video-responsive d-none">
                    <iframe data-src="//api.ortified.ws/embed/movie/93520"></iframe>
                </div>
                <div class="tabs-block__content video-inside video-responsive d-none" id="trailer-block">
                    <iframe data-src="https://www.youtube.com/embed/Mq_D3IMLyEE"></iframe>
                </div>
            </div>
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "https://hdvbua.pro/embed/1012162/e623cb5a2",
                "https://api.ortified.ws/embed/movie/93520",
            ),
            extractUaKinoCXPlayerUrls(document),
        )
    }

    @Test
    fun `extractOrtifiedHlsUrl extracts master m3u8 url from script`() {
        val script = """
            var opts = {
                source: {
                    hls: "https://example.com/media/master.m3u8?token=123"
                }
            };
        """.trimIndent()

        assertEquals(
            "https://example.com/media/master.m3u8?token=123",
            extractOrtifiedHlsUrl(script),
        )
    }

    @Test
    fun `extractHdvbuaFile extracts file value from script`() {
        val script = """
            var player = new Playerjs({
                id: "player_1012162",
                file: '[{"title":"Укр. Дуб. (TS)","file":"https://example.com/stream.m3u8"}]'
            });
        """.trimIndent()

        assertEquals(
            """[{"title":"Укр. Дуб. (TS)","file":"https://example.com/stream.m3u8"}]""",
            extractHdvbuaFile(script),
        )
    }

    @Test
    fun `parseEpisodeNumbers parses season and episode digits`() {
        val (s, ep) = parseEpisodeNumbers("3 сезон", "12 серія")
        assertEquals(3, s)
        assertEquals(12, ep)
    }

    @Test
    fun `flattenPlayerJson parses nested seasons dubs and episodes`() {
        val json = listOf(
            PlayerJson(
                title = "1 сезон",
                folder = listOf(
                    PlayerJson(
                        title = "ТЕТ",
                        folder = listOf(
                            PlayerJson(
                                title = "1 серія",
                                file = "https://example.com/s01e01.m3u8",
                                poster = "https://example.com/poster.jpg",
                                subtitle = "[UA]https://example.com/sub.vtt",
                            )
                        )
                    )
                )
            )
        )

        val flattened = flattenPlayerJson(json)
        assertEquals(1, flattened.size)
        val first = flattened.first()
        assertEquals("1 сезон", first.seasonName)
        assertEquals(1, first.seasonNumber)
        assertEquals("ТЕТ", first.dubName)
        assertEquals("1 серія", first.episodeName)
        assertEquals(1, first.episodeNumber)
        assertEquals("https://example.com/s01e01.m3u8", first.file)
        assertEquals("[UA]https://example.com/sub.vtt", first.subtitle)
    }

    @Test
    fun `isUaKinoCXComingSoon flags media absence`() {
        assertTrue(isUaKinoCXComingSoon(hasPlayableMedia = false))
        assertFalse(isUaKinoCXComingSoon(hasPlayableMedia = true))
    }
}
