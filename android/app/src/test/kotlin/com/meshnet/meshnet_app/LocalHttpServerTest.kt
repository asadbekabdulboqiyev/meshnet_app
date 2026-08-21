package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.localnet.LocalHttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

/**
 * LocalHttpServer testlari: real socket orqali HTTP/1.1 so'rovlar,
 * endpoint routing, 404, katta body drain.
 */
class LocalHttpServerTest {

    private lateinit var server: LocalHttpServer

    private val provider = object : LocalHttpServer.ContentProvider {
        override fun deviceName() = "Test Device"
        override fun deviceId() = "test-device-id"
        override fun knownHostsJson() = "{\"hosts\":[{\"hostname\":\"alpha\",\"deviceId\":\"id-1\",\"displayName\":\"Alpha\"}]}"
    }

    @Before
    fun setUp() {
        // Port 0 -> OS picks a free port (parallel-safe)
        server = LocalHttpServer(0, provider)
        assertTrue(server.start())
        assertTrue(server.isRunning)
        assertTrue(server.boundPort > 0)
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun request(raw: String): Pair<Int, String> {
        Socket("127.0.0.1", server.boundPort).use { sock ->
            sock.soTimeout = 5000
            sock.getOutputStream().write(raw.toByteArray(Charsets.ISO_8859_1))
            sock.getOutputStream().flush()
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
            val statusLine = reader.readLine() ?: return 0 to ""
            val code = statusLine.split(" ")[1].toInt()
            val headers = StringBuilder()
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                headers.append(line).append("\n")
                if (line.lowercase().startsWith("content-length:")) {
                    contentLength = line.substringAfter(":").trim().toInt()
                }
            }
            val body = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val r = reader.read(body, read, contentLength - read)
                if (r < 0) break
                read += r
            }
            return code to String(body, 0, read)
        }
    }

    @Test
    fun rootServesHtmlPage() {
        val (code, body) = request("GET / HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals(200, code)
        assertTrue(body.contains("LocalNet Node"))
        assertTrue(body.contains("Test Device"))
        assertTrue(body.contains("test-device-id"))
    }

    @Test
    fun infoServesJson() {
        val (code, body) = request("GET /info HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals(200, code)
        assertTrue(body.contains("\"deviceName\":\"Test Device\""))
        assertTrue(body.contains("\"service\":\"LocalNet\""))
    }

    @Test
    fun dnsServesHostsJson() {
        val (code, body) = request("GET /dns HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals(200, code)
        assertTrue(body.contains("\"hostname\":\"alpha\""))
        assertTrue(body.contains("\"displayName\":\"Alpha\""))
    }

    @Test
    fun unknownPathReturns404() {
        val (code, _) = request("GET /nope HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals(404, code)
    }

    @Test
    fun queryStringsIgnoredInRouting() {
        val (code, body) = request("GET /info?verbose=1 HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals(200, code)
        assertTrue(body.contains("\"deviceName\""))
    }

    @Test
    fun postMethodRejected() {
        val (code, _) = request("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 4\r\n\r\ndata")
        assertEquals(400, code)
    }

    @Test
    fun garbageRequestReturns400() {
        val (code, _) = request("GARBAGE\r\n\r\n")
        assertEquals(400, code)
    }

    @Test
    fun jsonEscapingHandlesSpecials() {
        assertEquals("a\\\"b", LocalHttpServer.escapeJson("a\"b"))
        assertEquals("a\\\\b", LocalHttpServer.escapeJson("a\\b"))
        assertEquals("a\\nb", LocalHttpServer.escapeJson("a\nb"))
        assertEquals("plain", LocalHttpServer.escapeJson("plain"))
    }

    @Test
    fun stopShutsDownCleanly() {
        server.stop()
        assertEquals(false, server.isRunning)
    }
}
