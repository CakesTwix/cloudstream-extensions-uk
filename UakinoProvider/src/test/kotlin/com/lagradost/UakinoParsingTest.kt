package com.lagradost

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jsoup.Jsoup
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import com.lagradost.nicehttp.Session
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import okhttp3.ResponseBody.Companion.toResponseBody

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

    @Test
    fun `movie fallback uses the original detail page`() {
        val movieUrl = "https://uakino.best/filmy/family/35377-toni-10.html"
        val ajaxUrl = "https://uakino.best/engine/ajax/playlists.php?news_id=35377"

        // Для фільму AJAX-відповідь може бути ERR_NOT_DATA, тому сторінку треба
        // повторно завантажувати за початковим URL, а не за URL AJAX-запиту.
        assertEquals(movieUrl, resolveUakinoDetailUrl(movieUrl, null, ajaxUrl))
    }

    @Test
    fun `episode fallback keeps the player request URL`() {
        val movieUrl = "https://uakino.best/filmy/family/35377-toni-10.html"
        val ajaxUrl = "https://uakino.best/engine/ajax/playlists.php?news_id=35377"

        assertEquals(ajaxUrl, resolveUakinoDetailUrl(movieUrl, "Серія 1", ajaxUrl))
    }

    @Test
    fun `tortuga file is decoded to a playable HLS URL`() {
        val encrypted =
            "tqu+pais3MLbmGNlaWdtSgJHVTM8OjE8ShwGH6/v4uaz19jQ0dOjv6f0kYaFg5ZafHJoSndNX1ojLiAGDAgAEhnx1c7t8cXYwOTz8Onugd3dxMwuYGNlMk1FVlw4aSNmKVs==="

        assertEquals(
            "https://calypso.tortuga.wtf/hls/trailers/south_park_bigger_longer__uncut_1999_8176/hls/index.m3u8",
            resolveUakinoStreamUrl(encrypted),
        )
    }

    @Test
    fun `invalid encrypted file is ignored`() {
        assertEquals(null, resolveUakinoStreamUrl("not-a-playable-file"))
    }

    @Test
    fun `resolveUakinoStreamUrls extracts direct m3u8 urls from bracketed and multi-source lists`() {
        val raw = "[1080p]https://calypso.tortuga.wtf/1080/index.m3u8 or [720p]https://calypso.tortuga.wtf/720/index.m3u8"
        val urls = resolveUakinoStreamUrls(raw)

        assertEquals(
            listOf(
                "https://calypso.tortuga.wtf/1080/index.m3u8",
                "https://calypso.tortuga.wtf/720/index.m3u8",
            ),
            urls,
        )
    }

    @Test
    fun `extractUakinoMoviePlayerUrls extracts schema video and iframes excluding trailer`() {
        val document = org.jsoup.Jsoup.parse(
            """
            <div itemscope itemtype="https://schema.org/Movie">
                <link itemprop="video" value="//ashdi.vip/vod/96531">
                <link itemprop="trailer" value="https://www.youtube.com/embed/VJsPZvs4yrA">
                <div class="box full-text visible">
                    <iframe id="pre" data-src="//tortuga.wtf/vod/3430"></iframe>
                </div>
                <div class="box full-text" id="overroll">
                    <iframe id="pre" src="https://www.youtube.com/embed/VJsPZvs4yrA"></iframe>
                </div>
            </div>
            """.trimIndent()
        )

        val players = extractUakinoMoviePlayerUrls(document)

        assertEquals(
            listOf(
                "https://ashdi.vip/vod/96531",
                "https://tortuga.wtf/vod/3430",
            ),
            players,
        )
    }

    @Test
    fun `movie data serialization and parsing roundtrip`() {
        val serialized = buildUakinoMovieData(
            pageUrl = "https://uakino.best/filmy/genre_comedy/3430-bez-vdchuttv.html",
            newsId = "3430",
            playerUrls = listOf("https://ashdi.vip/vod/16447", "https://tortuga.wtf/vod/3430")
        )

        val parsed = parseUakinoMovieData(serialized)

        assertEquals("https://uakino.best/filmy/genre_comedy/3430-bez-vdchuttv.html", parsed.pageUrl)
        assertEquals("3430", parsed.newsId)
        assertEquals(listOf("https://ashdi.vip/vod/16447", "https://tortuga.wtf/vod/3430"), parsed.playerUrls)
    }

    @Test
    fun `legacy movie url is parsed into UakinoMovieData`() {
        val parsed = parseUakinoMovieData("https://uakino.best/filmy/genre_comedy/3430-bez-vdchuttv.html")

        assertEquals("https://uakino.best/filmy/genre_comedy/3430-bez-vdchuttv.html", parsed.pageUrl)
        assertEquals("3430", parsed.newsId)
        assertEquals(emptyList<String>(), parsed.playerUrls)
    }

    @Test
    fun `announcement url without playable media is marked as coming soon`() {
        val document = Jsoup.parse(
            """
            <div class="full-quality">ТРЕЙЛЕР</div>
            <div class="solototle">Веріті</div>
            """.trimIndent()
        )

        assertTrue(
            isUakinoComingSoon(
                url = "https://uakino.best/anonsi/36524-veriti.html",
                document = document,
                hasPlayableMedia = false,
            )
        )
    }

    @Test
    fun `trailer quality without playable media is marked as coming soon`() {
        val document = Jsoup.parse(
            """
            <div class="full-quality">ТРЕЙЛЕР</div>
            """.trimIndent()
        )

        assertTrue(
            isUakinoComingSoon(
                url = "https://uakino.best/filmy/12345-movie.html",
                document = document,
                hasPlayableMedia = false,
            )
        )
    }

    @Test
    fun `content without playable media is marked as coming soon`() {
        val document = Jsoup.parse(
            """
            <div class="full-quality">BDRip 1080p</div>
            """.trimIndent()
        )

        assertTrue(
            isUakinoComingSoon(
                url = "https://uakino.best/filmy/12345-movie.html",
                document = document,
                hasPlayableMedia = false,
            )
        )
    }

    @Test
    fun `released content with playable media is not marked as coming soon`() {
        val document = Jsoup.parse(
            """
            <div class="full-quality">BDRip 1080p</div>
            """.trimIndent()
        )

        assertFalse(
            isUakinoComingSoon(
                url = "https://uakino.best/filmy/12345-movie.html",
                document = document,
                hasPlayableMedia = true,
            )
        )
    }

    @Test
    fun `released content with playable media is not marked as coming soon even if trailer or announcement text is present`() {
        val document = Jsoup.parse(
            """
            <div class="full-quality">АНОНС</div>
            <div class="film-info">
                <div class="fi-item">
                    <span class="fi-label">Переклад:</span>
                    <span class="fi-desc">Трейлер</span>
                </div>
            </div>
            """.trimIndent()
        )

        assertFalse(
            isUakinoComingSoon(
                url = "https://uakino.best/seriesss/drama_series/36336-amerykanska-istoriia-zhakhu-13-sezon.html",
                document = document,
                hasPlayableMedia = true,
            )
        )
    }

    @Test
    fun `Ashdi player referer keeps synthetic path and query but drops fragment`() {
        assertEquals(
            "https://ashdi.vip/vod/example?mode=synthetic",
            selectUakinoPlayerReferer(
                "https://ashdi.vip/vod/example?mode=synthetic#section",
                "https://uakino.best/"
            )
        )
    }

    @Test
    fun `player referer rejects userinfo protocols and malformed Ashdi hosts`() {
        val fallback = "https://uakino.best/"

        assertEquals(
            fallback,
            selectUakinoPlayerReferer("https://viewer:synthetic@ashdi.vip/vod/example", fallback)
        )
        assertEquals(
            fallback,
            selectUakinoPlayerReferer("ftp://ashdi.vip/vod/example", fallback)
        )
        assertEquals(
            fallback,
            selectUakinoPlayerReferer("https://ashdi.vip:99999/vod/example", fallback)
        )
        assertEquals(
            "https://ashdi.vip.test/",
            selectUakinoPlayerReferer("https://ashdi.vip.test/vod/example?mode=synthetic", fallback)
        )
    }

    @Test
    fun `other player referer stays at its synthetic origin and preserves port`() {
        assertEquals(
            "https://player.example.test:8443/",
            selectUakinoPlayerReferer(
                "https://player.example.test:8443/embed/example?mode=synthetic",
                "https://uakino.best/"
            )
        )
    }

    @Test
    fun `known MP4 URL keeps the direct link without discovery request`() = runTest {
        val streamUrl = "https://media.example.test/video/example.mp4?mode=synthetic"
        val requests = mutableListOf<Request>()
        val session = syntheticSession(requests) { request -> syntheticResponse(request, "not requested") }
        val links = mutableListOf<ExtractorLink>()

        emitUakinoM3u8Links(
            source = "Uakino",
            name = "Uakino - Synthetic",
            streamUrl = streamUrl,
            referer = "https://ashdi.vip/vod/example?mode=synthetic",
            session = session,
            callback = links::add,
        )

        assertTrue(requests.isEmpty())
        assertEquals(listOf(streamUrl), links.map { it.url })
        assertEquals(ExtractorLinkType.M3U8, links.single().type)
    }

    @Test
    fun `failed master fetch leaves the production direct link available`() = runTest {
        val masterUrl = "https://ashdi.vip/video/example/hls/index.m3u8"
        val requests = mutableListOf<Request>()
        val links = mutableListOf<ExtractorLink>()
        val session = syntheticSession(requests) { request ->
            assertEquals(listOf(masterUrl), links.map { it.url })
            throw IOException("synthetic playlist fetch failure")
        }

        emitUakinoM3u8Links(
            source = "Uakino",
            name = "Uakino - Synthetic dub",
            streamUrl = masterUrl,
            referer = "https://ashdi.vip/vod/example?mode=synthetic",
            session = session,
            callback = links::add,
        )

        assertEquals(listOf(masterUrl), requests.map { it.url.toString() })
        assertEquals(listOf(masterUrl), links.map { it.url })
    }

    @Test
    fun `master-only discovery sends selected referer and never fetches cross-origin variants`() = runTest {
        val selectedReferer = "https://ashdi.vip/vod/example?mode=synthetic"
        val masterUrl = "https://ashdi.vip/video/example/hls/test-playlist/index.m3u8"
        val variantUrl = "https://media.example.test/video/example/720p.m3u8"
        val requests = mutableListOf<Request>()
        val links = mutableListOf<ExtractorLink>()
        val session = syntheticSession(requests) { request ->
            assertEquals(listOf(masterUrl), links.map { it.url })
            assertEquals(masterUrl, request.url.toString())
            assertEquals(selectedReferer, request.header("Referer"))
            assertEquals(null, request.header("Cookie"))
            assertEquals(null, request.header("Origin"))
            assertEquals(null, request.header("Authorization"))
            syntheticResponse(
                request,
                """
                    #EXTM3U
                    #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720
                    $variantUrl
                """.trimIndent()
            )
        }

        emitUakinoM3u8Links(
            source = "Uakino",
            name = "Uakino - Synthetic dub",
            streamUrl = masterUrl,
            referer = selectedReferer,
            session = session,
            callback = links::add,
        )

        assertEquals(listOf(masterUrl), requests.map { it.url.toString() })
        assertEquals(masterUrl, links[0].url)
        assertEquals("Uakino", links[1].source)
        assertEquals("Uakino - Synthetic dub - 720p", links[1].name)
        assertEquals(variantUrl, links[1].url)
        assertEquals(720, links[1].quality)
        assertEquals("https://ashdi.vip/", links[1].referer)
        assertEquals(mapOf("Referer" to "https://ashdi.vip/"), links[1].headers)
        assertEquals(ExtractorLinkType.M3U8, links[1].type)
    }

    @Test
    fun `core parser keeps standalone qualities and excludes external audio and trick play`() {
        val masterUrl = "https://ashdi.vip/video/example/hls/test-playlist/index.m3u8"
        val playlist = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",URI="audio.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720,AUDIO="audio"
            audio-720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1600000,RESOLUTION=1920x1080
            1080.m3u8
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=300000,RESOLUTION=640x360,URI="iframe.m3u8"
        """.trimIndent()

        val links = parseUakinoMasterVariants(
            source = "Uakino",
            name = "Uakino - Synthetic dub",
            masterUrl = masterUrl,
            playerReferer = "https://ashdi.vip/vod/example?mode=synthetic",
            playlistText = playlist,
        )

        assertEquals(listOf("https://ashdi.vip/video/example/hls/test-playlist/1080.m3u8"), links.map { it.url })
        assertEquals(1080, links.single().quality)
        assertEquals("Uakino - Synthetic dub - 1080p", links.single().name)
        assertEquals("https://ashdi.vip/vod/example?mode=synthetic", links.single().headers["Referer"])
    }

    @Test
    fun `direct master remains when playlist is media malformed or redirected`() = runTest {
        val masterUrl = "https://ashdi.vip/video/example/hls/test-playlist/index.m3u8"
        val referer = "https://ashdi.vip/vod/example?mode=synthetic"
        val mediaRequests = mutableListOf<Request>()
        val mediaSession = syntheticSession(mediaRequests) { request ->
            syntheticResponse(
                request,
                """
                    #EXTM3U
                    #EXTINF:6,
                    segment.ts
                    #EXT-X-ENDLIST
                """.trimIndent(),
            )
        }
        val mediaLinks = mutableListOf<ExtractorLink>()
        emitUakinoM3u8Links("Uakino", "Uakino - Synthetic", masterUrl, referer, mediaSession, mediaLinks::add)
        assertEquals(listOf(masterUrl), mediaLinks.map { it.url })
        assertEquals(listOf(masterUrl), mediaRequests.map { it.url.toString() })

        val emptyRequests = mutableListOf<Request>()
        val emptySession = syntheticSession(emptyRequests) { request -> syntheticResponse(request, "") }
        val emptyLinks = mutableListOf<ExtractorLink>()
        emitUakinoM3u8Links("Uakino", "Uakino - Synthetic", masterUrl, referer, emptySession, emptyLinks::add)
        assertEquals(listOf(masterUrl), emptyLinks.map { it.url })
        assertEquals(listOf(masterUrl), emptyRequests.map { it.url.toString() })

        val redirectRequests = mutableListOf<Request>()
        val redirectSession = syntheticSession(redirectRequests) { request ->
            syntheticResponse(request, "", code = 302, location = "https://media.example.test/redirected.m3u8")
        }
        val redirectLinks = mutableListOf<ExtractorLink>()
        emitUakinoM3u8Links("Uakino", "Uakino - Synthetic", masterUrl, referer, redirectSession, redirectLinks::add)
        assertEquals(listOf(masterUrl), redirectLinks.map { it.url })
        assertEquals(listOf(masterUrl), redirectRequests.map { it.url.toString() })
    }

    @Test
    fun `HTTPS referer is omitted for HTTP master requests`() = runTest {
        val masterUrl = "http://ashdi.vip/video/example/hls/test-playlist/index.m3u8"
        val requests = mutableListOf<Request>()
        val session = syntheticSession(requests) { request -> syntheticResponse(request, "malformed") }
        val links = mutableListOf<ExtractorLink>()

        emitUakinoM3u8Links(
            source = "Uakino",
            name = "Uakino - Synthetic",
            streamUrl = masterUrl,
            referer = "https://ashdi.vip/vod/example?mode=synthetic",
            session = session,
            callback = links::add,
        )

        assertEquals(1, requests.size)
        assertEquals(null, requests.single().header("Referer"))
        assertEquals("", links.single().referer)
        assertTrue(links.single().headers.isEmpty())
        assertEquals(
            "",
            selectUakinoStreamReferer(
                "https://player.example.test/",
                "http://media.example.test/video/example.m3u8",
            ),
        )
    }

    @Test
    fun `oversized unknown-length body is bounded closed and keeps only the direct link`() = runTest {
        val masterUrl = "https://ashdi.vip/video/example/hls/index.m3u8"
        val body = SyntheticOversizedBody(totalBytes = UAKINO_MAX_PLAYLIST_BYTES * 4)
        val requests = mutableListOf<Request>()
        val links = mutableListOf<ExtractorLink>()
        val session = syntheticSession(requests) { request ->
            assertEquals(listOf(masterUrl), links.map { it.url })
            syntheticResponse(request, body)
        }

        emitUakinoM3u8Links(
            source = "Uakino",
            name = "Uakino - Synthetic",
            streamUrl = masterUrl,
            referer = "https://ashdi.vip/vod/example?mode=synthetic",
            session = session,
            callback = links::add,
        )

        assertEquals(UAKINO_MAX_PLAYLIST_BYTES, body.bytesRead)
        assertTrue(body.closed)
        assertEquals(listOf(masterUrl), links.map { it.url })
        assertEquals(listOf(masterUrl), requests.map { it.url.toString() })
    }

    @Test
    fun `production cancellation propagates and closes the response body`() = runTest {
        val masterUrl = "https://ashdi.vip/video/example/hls/index.m3u8"
        val cancellation = CancellationException("synthetic cancellation")
        val body = SyntheticCancellationBody(cancellation)
        val requests = mutableListOf<Request>()
        val links = mutableListOf<ExtractorLink>()
        val session = syntheticSession(requests) { request ->
            assertEquals(listOf(masterUrl), links.map { it.url })
            syntheticResponse(request, body)
        }

        var caught: CancellationException? = null
        try {
            emitUakinoM3u8Links(
                source = "Uakino",
                name = "Uakino - Synthetic",
                streamUrl = masterUrl,
                referer = "https://ashdi.vip/vod/example?mode=synthetic",
                session = session,
                callback = links::add,
            )
        } catch (error: CancellationException) {
            caught = error
        }

        assertTrue(caught === cancellation)
        assertTrue(body.closed)
        assertEquals(listOf(masterUrl), links.map { it.url })
        assertEquals(1, requests.size)
    }

    private fun syntheticSession(
        requests: MutableList<Request>,
        respond: (Request) -> Response,
    ): Session = Session(
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requests.add(request)
                respond(request)
            }
            .build()
    )

    private fun syntheticResponse(
        request: Request,
        text: String,
        code: Int = 200,
        location: String? = null,
    ): Response = syntheticResponse(
        request,
        text.toResponseBody("application/vnd.apple.mpegurl".toMediaType()),
        code,
        location,
    )

    private fun syntheticResponse(
        request: Request,
        body: ResponseBody,
        code: Int = 200,
        location: String? = null,
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("Synthetic")
        .apply { if (location != null) header("Location", location) }
        .body(body)
        .build()

    private class SyntheticOversizedBody(private val totalBytes: Int) : ResponseBody() {
        var bytesRead = 0
            private set
        var closed = false
            private set

        override fun contentType() = "application/vnd.apple.mpegurl".toMediaType()
        override fun contentLength() = -1L
        override fun source(): BufferedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (bytesRead >= totalBytes) return -1
                val count = minOf(byteCount, (totalBytes - bytesRead).toLong()).toInt()
                sink.write(ByteArray(count) { '#'.code.toByte() })
                bytesRead += count
                return count.toLong()
            }

            override fun timeout() = Timeout.NONE
            override fun close() {
                closed = true
            }
        }.buffer()
    }

    private class SyntheticCancellationBody(private val cancellation: CancellationException) : ResponseBody() {
        var closed = false
            private set

        override fun contentType() = "application/vnd.apple.mpegurl".toMediaType()
        override fun contentLength() = -1L
        override fun source(): BufferedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw cancellation
            override fun timeout() = Timeout.NONE
            override fun close() {
                closed = true
            }
        }.buffer()
    }
}

