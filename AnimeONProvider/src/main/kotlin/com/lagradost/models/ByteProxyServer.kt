package com.lagradost.models

import java.util.concurrent.ConcurrentHashMap

class ByteProxyServer(
    private val path: String,
    private val defaultContentType: String,
    private val sniffContentType: ((ByteArray) -> String)? = null
) {

    val cache = ConcurrentHashMap<String, ByteArray>()
    private val contentTypes = ConcurrentHashMap<String, String>()

    var resolver: ((String) -> ByteArray?)? = null
    var onStored: ((String) -> Unit)? = null

    var port: Int = 0
        private set

    @Synchronized
    fun ensureStarted() {
        if (port != 0) return

        val serverSocket = java.net.ServerSocket(0)
        port = serverSocket.localPort

        Thread {
            while (!serverSocket.isClosed) {
                try {
                    val client = serverSocket.accept()

                    Thread {
                        try {
                            handle(client)
                        } catch (e: Exception) {
                        } finally {
                            try {
                                client.close()
                            } catch (_: Exception) {
                            }
                        }
                    }.also { it.isDaemon = true }.start()
                } catch (e: Exception) {
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    fun url(key: String): String = "http://127.0.0.1:$port/$path?$key"

    fun has(key: String): Boolean = cache.containsKey(key)

    fun put(key: String, bytes: ByteArray, contentType: String? = null) {
        cache[key] = bytes
        if (contentType != null) contentTypes[key] = contentType
    }

    fun newKey(ext: String = ""): String {
        return java.util.UUID.randomUUID().toString().replace("-", "") + ext
    }

    private fun handle(client: java.net.Socket) {
        val line = client.getInputStream().bufferedReader().readLine() ?: return
        val key = line.substringAfter("?").substringBefore(" ").trim()

        var body = cache[key]

        if (body == null) {
            body = resolver?.invoke(key)
            if (body != null && body.isNotEmpty()) {
                cache[key] = body
                onStored?.invoke(key)
            }
        }

        val out = client.getOutputStream()

        if (body == null || body.isEmpty()) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        } else {
            val contentType = contentTypes[key] ?: sniffContentType?.invoke(body) ?: defaultContentType

            val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: public, max-age=86400\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"

            out.write(response.toByteArray())
            out.write(body)
        }

        out.flush()
    }
}