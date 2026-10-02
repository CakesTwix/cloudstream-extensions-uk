package com.lagradost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnitubeSubtitlesTest {

    @Test
    fun `playerjs віддає кілька треків з назвами`() {
        val subtitles = parsePlayerJsSubtitles(
            "[Повні]https://ashdi.vip/subs/full.vtt,[Написи]https://ashdi.vip/subs/signs.vtt"
        )

        assertEquals(2, subtitles.size)
        assertEquals("Повні", subtitles[0].label)
        assertEquals("https://ashdi.vip/subs/full.vtt", subtitles[0].url)
        assertEquals("Написи", subtitles[1].label)
        assertEquals("https://ashdi.vip/subs/signs.vtt", subtitles[1].url)
    }

    @Test
    fun `порожнє поле subtitle не дає треків`() {
        assertTrue(parsePlayerJsSubtitles("").isEmpty())
        assertTrue(parsePlayerJsSubtitles(null).isEmpty())
        assertTrue(parsePlayerJsSubtitles("   ").isEmpty())
    }

    @Test
    fun `посилання без дужок отримує типову назву`() {
        val subtitles = parsePlayerJsSubtitles("https://ashdi.vip/subs/only.vtt")

        assertEquals(1, subtitles.size)
        assertEquals(DEFAULT_SUBTITLE_LABEL, subtitles[0].label)
        assertEquals("https://ashdi.vip/subs/only.vtt", subtitles[0].url)
    }

    @Test
    fun `однакові посилання не дублюються`() {
        val subtitles = parsePlayerJsSubtitles(
            "[Повні]https://a.vtt,[Full]https://a.vtt"
        )

        assertEquals(1, subtitles.size)
        assertEquals("Повні", subtitles[0].label)
    }

    @Test
    fun `форсований трек у HLS позначається у назві`() {
        val playlist = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Українські",LANGUAGE="uk",FORCED=YES,URI="subs/uk_forced.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Дубляж",URI="audio/playlist.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480
            /video/v_0/playlist.m3u8
        """.trimIndent()

        val subtitles = parseHlsSubtitles(
            playlist,
            "https://media.example.com/anime/ep001/master.m3u8?v=1",
        )

        assertEquals(1, subtitles.size)
        assertEquals("Українські (форсовані)", subtitles[0].label)
        assertEquals(
            "https://media.example.com/anime/ep001/subs/uk_forced.m3u8",
            subtitles[0].url,
        )
    }

    @Test
    fun `кома всередині лапок не ламає розбір атрибутів HLS`() {
        val playlist = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Повні, студія А",LANGUAGE="uk",URI="/subs/a.vtt"
        """.trimIndent()

        val subtitles = parseHlsSubtitles(playlist, "https://media.example.com/ep/master.m3u8")

        assertEquals(1, subtitles.size)
        assertEquals("Повні, студія А", subtitles[0].label)
        assertEquals("https://media.example.com/subs/a.vtt", subtitles[0].url)
    }

    @Test
    fun `плейлист без треків субтитрів не дає результату`() {
        val playlist = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\n/video/v_0/playlist.m3u8"

        assertTrue(parseHlsSubtitles(playlist, "https://media.example.com/ep/master.m3u8").isEmpty())
        assertTrue(parseHlsSubtitles(null, "https://media.example.com/ep/master.m3u8").isEmpty())
    }

    @Test
    fun `вже позначений форсований трек не дублює позначку`() {
        assertEquals("Forced", markForcedSubtitle("Forced", true))
        assertEquals("Написи", markForcedSubtitle("Написи", true))
        assertEquals("Повні", markForcedSubtitle("Повні", false))
        assertEquals("English (форсовані)", markForcedSubtitle("English", true))
    }

    @Test
    fun `посилання приводяться до абсолютного вигляду`() {
        val base = "https://media.example.com/anime/ep001/master.m3u8?v=7"

        assertEquals("https://cdn.example.com/a.vtt", resolveAnitubeUrl(base, "https://cdn.example.com/a.vtt"))
        assertEquals("https://cdn.example.com/a.vtt", resolveAnitubeUrl(base, "//cdn.example.com/a.vtt"))
        assertEquals("https://media.example.com/subs/a.vtt", resolveAnitubeUrl(base, "/subs/a.vtt"))
        assertEquals("https://media.example.com/anime/ep001/subs/a.vtt", resolveAnitubeUrl(base, "subs/a.vtt"))
        assertNull(resolveAnitubeUrl(base, "  "))
    }

    @Test
    fun `конфіг плеєра дістається з дужками у вкладених рядках`() {
        val html = """
            <script>
            window.FENIX_PLAYLIST = {"title":"А { не дужка }","episodes":[{"number":1,"subtitles":[]}]};
            </script>
        """.trimIndent()

        assertEquals(
            """{"title":"А { не дужка }","episodes":[{"number":1,"subtitles":[]}]}""",
            extractJsObject(html, "window.FENIX_PLAYLIST"),
        )
    }

    @Test
    fun `екранована лапка не завершує рядок у конфігу`() {
        val html = """window.FENIX_MEDIA_ACCESS = {"origin":"https://media.fenixplay.xyz","token":"a\"b}"};"""

        assertEquals(
            """{"origin":"https://media.fenixplay.xyz","token":"a\"b}"}""",
            extractJsObject(html, "window.FENIX_MEDIA_ACCESS"),
        )
    }

    @Test
    fun `відсутній маркер конфігу дає null`() {
        assertNull(extractJsObject("<script>var a = 1;</script>", "window.FENIX_PLAYLIST"))
    }
}
