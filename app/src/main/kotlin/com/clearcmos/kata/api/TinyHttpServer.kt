package com.clearcmos.kata.api

import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.Executors

data class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: String
)

data class HttpResponse(val status: Int, val body: String, val contentType: String = "application/json; charset=utf-8")

/**
 * A minimal HTTP/1.1 server bound to loopback.
 *
 * Hand-rolled rather than pulled in, because the whole client surface is one CLI on the other
 * end of `adb forward` and a dependency here would be more code than this file. It answers one
 * request per connection and closes: no keep-alive, no chunked encoding, no TLS.
 */
class TinyHttpServer(private val port: Int, private val handler: (HttpRequest) -> HttpResponse) {
    private var serverSocket: ServerSocket? = null
    private val workers =
        Executors.newFixedThreadPool(WORKER_THREADS) { runnable ->
            Thread(runnable, "kata-api").apply { isDaemon = true }
        }

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        Thread({ acceptLoop() }, "kata-api-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        workers.shutdownNow()
    }

    private fun acceptLoop() {
        try {
            // Loopback only. Nothing off-device can reach this even on an open network.
            val socket = ServerSocket(port, BACKLOG, InetAddress.getLoopbackAddress())
            serverSocket = socket
            Log.i(TAG, "control API listening on 127.0.0.1:$port")
            while (running) {
                val client =
                    try {
                        socket.accept()
                    } catch (e: IOException) {
                        if (running) Log.e(TAG, "accept failed", e)
                        break
                    }
                workers.execute { serve(client) }
            }
        } catch (e: IOException) {
            Log.e(TAG, "could not bind port $port", e)
        } finally {
            runCatching { serverSocket?.close() }
        }
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val response =
                try {
                    val request = parse(BufferedInputStream(socket.getInputStream()))
                    if (request == null) {
                        HttpResponse(400, """{"error":"malformed request"}""")
                    } else {
                        handler(request)
                    }
                } catch (e: SocketTimeoutException) {
                    // A client that announces a body and then stops sending would otherwise hold
                    // a worker for the whole socket timeout. There are only two workers, so a
                    // couple of truncated requests would stall every other call to the API.
                    Log.w(TAG, "client stopped mid-request", e)
                    HttpResponse(408, """{"error":"request timed out"}""")
                } catch (e: Exception) {
                    Log.e(TAG, "handler threw", e)
                    HttpResponse(500, """{"error":${quote(e.message ?: e.javaClass.simpleName)}}""")
                }
            write(socket, response)
        }
    }

    private fun parse(input: BufferedInputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            headers[line.take(separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body =
            if (length > 0) {
                if (length > MAX_BODY_BYTES) return null
                val buffer = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(buffer, read, length - read)
                    if (n < 0) break
                    read += n
                }
                String(buffer, 0, read, Charsets.UTF_8)
            } else {
                ""
            }

        val questionMark = target.indexOf('?')
        val path = if (questionMark >= 0) target.take(questionMark) else target
        val query = if (questionMark >= 0) parseQuery(target.substring(questionMark + 1)) else emptyMap()
        return HttpRequest(method, path.trimEnd('/').ifEmpty { "/" }, query, headers, body)
    }

    private fun parseQuery(raw: String): Map<String, String> = raw
        .split("&")
        .filter { it.isNotEmpty() }
        .associate { pair ->
            val index = pair.indexOf('=')
            if (index < 0) {
                decode(pair) to ""
            } else {
                decode(pair.take(index)) to decode(pair.substring(index + 1))
            }
        }

    private fun decode(text: String): String = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)

    private fun readLine(input: BufferedInputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (builder.isEmpty()) null else builder.toString()
            if (byte == '\n'.code) return builder.toString().removeSuffix("\r")
            builder.append(byte.toChar())
            if (builder.length > MAX_LINE_CHARS) return null
        }
    }

    private fun write(socket: Socket, response: HttpResponse) {
        val payload = response.body.toByteArray(Charsets.UTF_8)
        val head =
            buildString {
                append("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n")
                append("Content-Type: ${response.contentType}\r\n")
                append("Content-Length: ${payload.size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            write(payload)
            flush()
        }
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        201 -> "Created"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        422 -> "Unprocessable Entity"
        else -> "Internal Server Error"
    }

    private fun quote(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        const val TAG = "KataApi"
        const val BACKLOG = 8
        const val WORKER_THREADS = 2
        const val SOCKET_TIMEOUT_MS = 3_000
        const val MAX_BODY_BYTES = 4 * 1024 * 1024
        const val MAX_LINE_CHARS = 8192
    }
}
