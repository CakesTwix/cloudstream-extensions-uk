package com.lagradost

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSource
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class UAFlixZetvideoInterceptorTest {
    @Test
    fun `empty responses retry with backoff and return the first nonempty manifest`() {
        val request = playbackRequest()
        val firstBody = TrackingBody(byteArrayOf(), 0L)
        val secondBody = TrackingBody(byteArrayOf(), -1L)
        val playlist = "#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray()
        val validBody = TrackingBody(playlist, -1L)
        val firstResponse = response(request, firstBody)
        val secondResponse = response(request, secondBody)
        val validResponse = response(request, validBody)
        val chain = QueueChain(request, listOf(firstResponse, secondResponse, validResponse))
        val delays = mutableListOf<Long>()

        val result = UAFlixZetvideoInterceptor(
            waitBeforeRetry = { call, delay ->
                assertFalse(call.isCanceled())
                delays += delay
            },
        ).intercept(chain)

        assertSame(validResponse, result)
        assertEquals(listOf(2_000L, 5_000L), delays)
        assertEquals(3, chain.requests.size)
        assertTrue(firstBody.closed)
        assertTrue(secondBody.closed)
        assertFalse(validBody.closed)
        assertEquals("https://zetvideo.net/vid/test/hls/1080/index.m3u8", chain.requests[1].url.toString())
        assertEquals(request.headers.values("Referer"), chain.requests[1].headers.values("Referer"))
        assertEquals(request.headers.values("X-Playback"), chain.requests[1].headers.values("X-Playback"))
        assertEquals(listOf("max-age=60", "no-cache"), chain.requests[1].headers.values("Cache-Control"))
        assertEquals(listOf("max-age=60", "no-cache"), chain.requests[2].headers.values("Cache-Control"))
        assertEquals("#EXTM3U\n#EXT-X-VERSION:3\n", result.body!!.source().readUtf8())
    }

    @Test
    fun `three retries exhaust after four total attempts and return final empty response`() {
        val request = playbackRequest()
        val bodies = List(4) { TrackingBody(byteArrayOf(), 0L) }
        val responses = bodies.map { response(request, it) }
        val chain = QueueChain(request, responses)
        val delays = mutableListOf<Long>()

        val result = UAFlixZetvideoInterceptor(
            waitBeforeRetry = { _, delay -> delays += delay },
        ).intercept(chain)

        assertSame(responses.last(), result)
        assertEquals(listOf(2_000L, 5_000L, 10_000L), delays)
        assertEquals(4, chain.requests.size)
        assertTrue(bodies.take(3).all { it.closed })
        assertFalse(bodies.last().closed)
    }

    @Test
    fun `nonempty unknown length manifest and non-200 empty response pass through unchanged`() {
        val request = playbackRequest()
        val playlistBody = TrackingBody("#EXTM3U\n".toByteArray(), -1L)
        val playlistResponse = response(request, playlistBody)
        val playlistChain = QueueChain(request, listOf(playlistResponse))
        var waits = 0

        val playlistResult = UAFlixZetvideoInterceptor(
            retryDelaysMs = listOf(1L),
            waitBeforeRetry = { _, _ -> waits++ },
        ).intercept(playlistChain)

        assertSame(playlistResponse, playlistResult)
        assertEquals(1, playlistChain.requests.size)
        assertEquals(0, waits)
        assertFalse(playlistBody.closed)
        assertEquals("#EXTM3U\n", playlistResult.body!!.source().readUtf8())

        val errorBody = TrackingBody(byteArrayOf(), 0L)
        val errorResponse = response(request, errorBody, code = 503)
        val errorChain = QueueChain(request, listOf(errorResponse))
        val errorResult = UAFlixZetvideoInterceptor(
            retryDelaysMs = listOf(1L),
            waitBeforeRetry = { _, _ -> waits++ },
        ).intercept(errorChain)

        assertSame(errorResponse, errorResult)
        assertEquals(1, errorChain.requests.size)
        assertEquals(0, waits)
        assertFalse(errorBody.closed)
    }

    @Test
    fun `only exact host GET m3u8 paths are eligible`() {
        val requests = listOf(
            playbackRequest("https://sub.zetvideo.net/vid/test/index.m3u8"),
            playbackRequest("https://zetvideo.net.evil.test/vid/test/index.m3u8"),
            playbackRequest("https://other.test/vid/test/index.m3u8"),
            playbackRequest("https://zetvideo.net/vid/test/segment.ts"),
            playbackRequest("https://zetvideo.net/player/subtitles/ua.vtt"),
            playbackRequest(method = "POST"),
        )
        requests.forEach { request ->
            val body = TrackingBody(byteArrayOf(), 0L)
            val response = response(request, body)
            val chain = QueueChain(request, listOf(response))
            var waits = 0

            val result = UAFlixZetvideoInterceptor(
                retryDelaysMs = listOf(1L),
                waitBeforeRetry = { _, _ -> waits++ },
            ).intercept(chain)

            assertSame(response, result)
            assertEquals(1, chain.requests.size)
            assertEquals(0, waits)
            assertFalse(body.closed)
        }
    }

    @Test
    fun `canceled call aborts the retry wait and closes the discarded response`() {
        val request = playbackRequest()
        val body = TrackingBody(byteArrayOf(), 0L)
        val chain = QueueChain(request, listOf(response(request, body)), canceled = true)

        val error = runCatching { UAFlixZetvideoInterceptor().intercept(chain) }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(body.closed)
        assertEquals(1, chain.requests.size)
    }

    @Test
    fun `interrupted retry wait preserves thread interrupt status`() {
        val request = playbackRequest()
        val body = TrackingBody(byteArrayOf(), 0L)
        val chain = QueueChain(request, listOf(response(request, body)))
        Thread.currentThread().interrupt()

        try {
            val error = runCatching { UAFlixZetvideoInterceptor().intercept(chain) }.exceptionOrNull()

            assertTrue(error is InterruptedIOException)
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(body.closed)
            assertEquals(1, chain.requests.size)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `network IO failure during a retry propagates`() {
        val request = playbackRequest()
        val body = TrackingBody(byteArrayOf(), 0L)
        val chain = QueueChain(request, listOf(response(request, body)), failWhenExhausted = true)

        val error = runCatching {
            UAFlixZetvideoInterceptor(
                waitBeforeRetry = { _, _ -> },
            ).intercept(chain)
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals("network failure", error?.message)
        assertTrue(body.closed)
        assertEquals(2, chain.requests.size)
    }

    @Test
    fun `real OkHttp application pipeline retries and closes discarded bodies`() {
        val request = playbackRequest()
        val firstBody = TrackingBody(byteArrayOf(), 0L)
        val secondBody = TrackingBody(byteArrayOf(), -1L)
        val playlist = "#EXTM3U\\n#EXT-X-VERSION:3\\n".toByteArray()
        val validBody = TrackingBody(playlist, -1L)
        val bodies = mutableListOf(firstBody, secondBody, validBody)
        val requests = mutableListOf<Request>()
        val delays = mutableListOf<Long>()
        val client = OkHttpClient.Builder()
            .addInterceptor(
                UAFlixZetvideoInterceptor(
                    retryDelaysMs = listOf(2_000L, 5_000L),
                    waitBeforeRetry = { _, delay -> delays += delay },
                )
            )
            .addInterceptor(Interceptor { chain ->
                requests += chain.request()
                response(chain.request(), bodies.removeAt(0))
            })
            .build()

        val result = client.newCall(request).execute()
        try {
            assertEquals(3, requests.size)
            assertEquals(listOf(2_000L, 5_000L), delays)
            assertTrue(firstBody.closed)
            assertTrue(secondBody.closed)
            assertFalse(validBody.closed)
            assertEquals(listOf("max-age=60", "no-cache"), requests[1].headers.values("Cache-Control"))
            assertEquals(listOf("max-age=60", "no-cache"), requests[2].headers.values("Cache-Control"))
            assertEquals("#EXTM3U\\n#EXT-X-VERSION:3\\n", result.body!!.source().readUtf8())
        } finally {
            result.close()
        }
        assertTrue(validBody.closed)
    }

    @Test
    fun `real OkHttp call cancellation stops retrying after closing the empty response`() {
        val request = playbackRequest()
        val emptyBody = TrackingBody(byteArrayOf(), 0L)
        val attempts = AtomicInteger()
        val client = OkHttpClient.Builder()
            .addInterceptor(UAFlixZetvideoInterceptor(retryDelaysMs = listOf(60_000L)))
            .addInterceptor(Interceptor { chain ->
                attempts.incrementAndGet()
                response(chain.request(), emptyBody)
            })
            .build()
        val call = client.newCall(request)
        val returnedResponse = AtomicReference<Response?>()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                returnedResponse.set(call.execute())
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply { start() }

        try {
            assertTrue(emptyBody.closedSignal.await(2, TimeUnit.SECONDS))
            call.cancel()
            worker.join(2_000L)

            assertFalse("Canceled call did not finish", worker.isAlive)
            assertTrue(failure.get() is IOException)
            assertEquals(1, attempts.get())
            assertTrue(emptyBody.closed)
        } finally {
            call.cancel()
            worker.join(2_000L)
            returnedResponse.get()?.close()
        }
    }

    private fun playbackRequest(
        url: String = "https://zetvideo.net/vid/test/hls/1080/index.m3u8",
        method: String = "GET",
    ): Request = Request.Builder()
        .url(url)
        .header("Referer", "https://tortuga.wtf/")
        .header("X-Playback", "keep-me")
        .header("Cache-Control", "max-age=60")
        .method(method, if (method == "GET") null else ByteArray(0).toRequestBody())
        .build()

    private fun response(
        request: Request,
        body: TrackingBody,
        code: Int = 200,
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200) "OK" else "Unavailable")
        .body(body)
        .build()

    private class TrackingBody(
        bytes: ByteArray,
        private val declaredLength: Long,
    ) : ResponseBody() {
        private val buffer = Buffer().write(bytes)
        val closedSignal = CountDownLatch(1)
        var closed = false
            private set

        override fun contentType(): MediaType = "application/vnd.apple.mpegurl".toMediaType()
        override fun contentLength(): Long = declaredLength
        override fun source(): BufferedSource = buffer

        override fun close() {
            closed = true
            closedSignal.countDown()
            super.close()
        }
    }

    private class QueueChain(
        private val initialRequest: Request,
        responses: List<Response>,
        canceled: Boolean = false,
        private val failWhenExhausted: Boolean = false,
    ) : Interceptor.Chain {
        private val queuedResponses = responses.toMutableList()
        private val testCall = TestCall(initialRequest, canceled)
        val requests = mutableListOf<Request>()

        override fun request(): Request = initialRequest
        override fun proceed(request: Request): Response {
            requests += request
            if (queuedResponses.isEmpty()) {
                if (failWhenExhausted) throw IOException("network failure")
                throw AssertionError("No test response configured for attempt ${requests.size}")
            }
            return queuedResponses.removeAt(0)
        }

        override fun connection(): Connection? = null
        override fun call(): Call = testCall
        override fun connectTimeoutMillis(): Int = 1_000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 1_000
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 1_000
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    private class TestCall(
        private val originalRequest: Request,
        private var canceled: Boolean,
    ) : Call {
        override fun request(): Request = originalRequest
        override fun execute(): Response = throw UnsupportedOperationException()
        override fun enqueue(responseCallback: Callback) = throw UnsupportedOperationException()
        override fun cancel() {
            canceled = true
        }

        override fun isExecuted(): Boolean = false
        override fun isCanceled(): Boolean = canceled
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = TestCall(originalRequest, canceled)
    }
}
