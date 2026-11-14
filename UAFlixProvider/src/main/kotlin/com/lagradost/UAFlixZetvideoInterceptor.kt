package com.lagradost

import java.util.logging.Logger
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

private const val CANCEL_CHECK_INTERVAL_MS = 100L
private val retryLogger = Logger.getLogger("UAFlix")

internal class UAFlixZetvideoInterceptor(
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
    private val waitBeforeRetry: (Call, Long) -> Unit = ::interruptibleWait,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.isZetvideoHlsManifest()) return chain.proceed(request)

        var response = chain.proceed(request)
        if (!response.isEmptySuccessfulManifest()) return response

        retryDelaysMs.forEachIndexed { retryIndex, delayMs ->
            response.close()
            retryLogger.fine("Empty zetvideo HLS manifest; retry ${retryIndex + 1}/${retryDelaysMs.size} after ${delayMs}ms")
            waitBeforeRetry(chain.call(), delayMs)

            val retryRequest = request.newBuilder()
                .addHeader("Cache-Control", "no-cache")
                .build()
            response = chain.proceed(retryRequest)
            if (!response.isEmptySuccessfulManifest()) return response
        }

        return response
    }

    private fun Request.isZetvideoHlsManifest(): Boolean =
        method == "GET" && url.host == ZETVIDEO_HOST && url.encodedPath.endsWith(".m3u8", ignoreCase = true)

    private fun Response.isEmptySuccessfulManifest(): Boolean {
        if (code != 200) return false
        val responseBody = body
        return try {
            when {
                responseBody.contentLength() == 0L -> true
                responseBody.contentLength() > 0L -> false
                else -> !responseBody.source().request(1L)
            }
        } catch (error: IOException) {
            close()
            throw error
        }
    }

    private companion object {
        const val ZETVIDEO_HOST = "zetvideo.net"
        val DEFAULT_RETRY_DELAYS_MS = listOf(2_000L, 5_000L, 10_000L)
    }
}

private fun interruptibleWait(call: Call, delayMs: Long) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs)
    while (true) {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("Interrupted while waiting to retry zetvideo manifest")
        }
        if (call.isCanceled()) throw IOException("Canceled")

        val remainingNanos = deadline - System.nanoTime()
        if (remainingNanos <= 0L) return

        val remainingMs = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)
        try {
            Thread.sleep(minOf(CANCEL_CHECK_INTERVAL_MS, remainingMs))
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted while waiting to retry zetvideo manifest").apply {
                initCause(error)
            }
        }
    }
}
