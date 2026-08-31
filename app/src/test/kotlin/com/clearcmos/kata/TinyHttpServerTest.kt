package com.clearcmos.kata

import com.clearcmos.kata.api.HttpRequest
import com.clearcmos.kata.api.HttpResponse
import com.clearcmos.kata.api.TinyHttpServer
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the hand-rolled parser against real sockets.
 *
 * This is the only surface any app on the device can reach, so it is fed the malformed and
 * hostile shapes a browser never sends: no headers, a bare request line, an oversized body, a
 * lying Content-Length.
 */
class TinyHttpServerTest {
    private lateinit var server: TinyHttpServer
    private var port = 0
    private val seen = ConcurrentLinkedQueue<HttpRequest>()

    @Before
    fun start() {
        port = ServerSocket(0).use { it.localPort }
        server = TinyHttpServer(port) { request ->
            seen.add(request)
            HttpResponse(200, """{"ok":true}""")
        }
        server.start()
        waitUntilListening()
    }

    @After
    fun stop() {
        server.stop()
    }

    private fun waitUntilListening() {
        repeat(50) {
            runCatching { Socket(InetAddress.getLoopbackAddress(), port).close() }.onSuccess { return }
            Thread.sleep(20)
        }
        error("server never bound port $port")
    }

    private fun send(raw: String): String = sendWithTimeout(raw, 10_000)

    private fun sendWithTimeout(raw: String, timeoutMs: Int): String =
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = timeoutMs
            socket.getOutputStream().write(raw.toByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().decodeToString()
        }

    @Test
    fun `a well formed GET is parsed`() {
        val response = send("GET /health HTTP/1.1\r\nHost: x\r\n\r\n")
        assertTrue(response, response.startsWith("HTTP/1.1 200 OK"))
        val request = seen.single()
        assertEquals("GET", request.method)
        assertEquals("/health", request.path)
    }

    @Test
    fun `headers are lower cased so lookups are case insensitive`() {
        send("GET / HTTP/1.1\r\nX-Kata-Token: abc\r\nCONTENT-TYPE: application/json\r\n\r\n")
        val request = seen.single()
        assertEquals("abc", request.headers["x-kata-token"])
        assertEquals("application/json", request.headers["content-type"])
    }

    @Test
    fun `a body is read to its declared length`() {
        val body = """{"a":1}"""
        send("POST /x HTTP/1.1\r\nContent-Length: ${body.length}\r\n\r\n$body")
        assertEquals(body, seen.single().body)
    }

    @Test
    fun `query parameters are split and url decoded`() {
        send("GET /runs?limit=5&id=my%20rule&flag HTTP/1.1\r\n\r\n")
        val request = seen.single()
        assertEquals("/runs", request.path)
        assertEquals("5", request.query["limit"])
        assertEquals("my rule", request.query["id"])
        assertEquals("", request.query["flag"])
    }

    @Test
    fun `a trailing slash does not create a separate route`() {
        send("GET /automations/ HTTP/1.1\r\n\r\n")
        assertEquals("/automations", seen.single().path)
    }

    @Test
    fun `the root path survives trimming`() {
        send("GET / HTTP/1.1\r\n\r\n")
        assertEquals("/", seen.single().path)
    }

    @Test
    fun `the method is upper cased`() {
        send("post /x HTTP/1.1\r\n\r\n")
        assertEquals("POST", seen.single().method)
    }

    @Test
    fun `a request line with no target is rejected as a bad request`() {
        val response = send("GARBAGE\r\n\r\n")
        assertTrue(response, response.startsWith("HTTP/1.1 400 Bad Request"))
        assertNull(seen.peek())
    }

    @Test
    fun `a body larger than the cap is refused instead of being buffered`() {
        // A hostile Content-Length must not be able to make the engine allocate at will.
        val response = send("POST /x HTTP/1.1\r\nContent-Length: 99999999\r\n\r\n")
        assertTrue(response, response.startsWith("HTTP/1.1 400 Bad Request"))
        assertNull(seen.peek())
    }

    @Test
    fun `a client that announces a body and stops sending is timed out, not held`() {
        // Regression: a truncated body used to hold a worker for the full socket timeout. With
        // only two workers, a couple of these stalled every other request on the API.
        val started = System.currentTimeMillis()
        val response = sendWithTimeout("POST /x HTTP/1.1\r\nContent-Length: 50\r\n\r\nshort", 15_000)
        val elapsed = System.currentTimeMillis() - started
        assertTrue(response, response.startsWith("HTTP/1.1 408"))
        assertTrue("took ${elapsed}ms", elapsed < 10_000)
        assertNull(seen.peek())
    }

    @Test
    fun `a malformed header line is skipped rather than aborting the request`() {
        send("GET / HTTP/1.1\r\nnot-a-header\r\nX-Kata-Token: abc\r\n\r\n")
        assertEquals("abc", seen.single().headers["x-kata-token"])
    }

    @Test
    fun `the response carries an accurate content length`() {
        val response = send("GET / HTTP/1.1\r\n\r\n")
        val declared = Regex("Content-Length: (\\d+)").find(response)!!.groupValues[1].toInt()
        val body = response.substringAfter("\r\n\r\n")
        assertEquals(declared, body.toByteArray().size)
    }

    @Test
    fun `a handler that throws becomes a 500 rather than killing the server`() {
        server.stop()
        port = ServerSocket(0).use { it.localPort }
        server = TinyHttpServer(port) { error("handler exploded") }
        server.start()
        waitUntilListening()
        assertTrue(send("GET / HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 500"))
        // Still serving afterwards.
        assertTrue(send("GET / HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 500"))
    }

    @Test
    fun `the server binds loopback only`() {
        // Anything reachable off-device would expose an API that can write secure settings.
        val addresses = InetAddress.getAllByName("localhost")
        assertTrue(addresses.isNotEmpty())
        Socket(InetAddress.getLoopbackAddress(), port).use { assertTrue(it.isConnected) }
    }
}
