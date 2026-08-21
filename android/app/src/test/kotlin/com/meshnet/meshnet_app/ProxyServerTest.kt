package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.localnet.vpn.ProxyServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 5 integratsiya testlari: real ProxyServer + real target HTTP server,
 * hammasi loopback'da. CONNECT tunnel, plain HTTP forward, health endpoint,
 * access control va tunnel limit tekshiruvi.
 */
class ProxyServerTest {

    private lateinit var proxy: ProxyServer
    private var proxyPort = -1

    /** Minimal origin server: one request -> fixed response, then closes. */
    private var targetServer: ServerSocket? = null
    private var targetPort = -1
    private val seenByTarget = StringBuilder()
    private val targetGotRequest = CountDownLatch(1)
    @Volatile
    private var targetThread: Thread? = null

    @Before
    fun setUp() {
        // Target "web server" on an ephemeral port
        val ss = ServerSocket(0)
        ss.reuseAddress = true
        targetServer = ss
        targetPort = ss.localPort
        targetThread = Thread {
            while (!ss.isClosed) {
                val client = try {
                    ss.accept()
                } catch (_: Exception) {
                    return@Thread
                }
                handleTarget(client)
            }
        }.apply { isDaemon = true; start() }

        proxy = ProxyServer(port = 0)
        assertTrue(proxy.start())
        proxyPort = proxy.boundPort
    }

    @After
    fun tearDown() {
        proxy.stop()
        try {
            targetServer?.close()
        } catch (_: Exception) {
        }
    }

    private fun handleTarget(client: Socket) {
        try {
            client.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1))
            val line = reader.readLine() ?: return
            synchronized(seenByTarget) { seenByTarget.append(line).append('\n') }
            // Drain headers
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
            }
            val body = "HELLO-FROM-TARGET".toByteArray(StandardCharsets.UTF_8)
            val resp = (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(StandardCharsets.US_ASCII) + body
            client.getOutputStream().write(resp)
            client.getOutputStream().flush()
        } catch (_: Exception) {
        } finally {
            targetGotRequest.countDown()
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun connectThroughProxy(): Socket {
        val s = Socket("127.0.0.1", proxyPort)
        s.soTimeout = 5000
        val out = BufferedOutputStream(s.getOutputStream())
        out.write("CONNECT 127.0.0.1:$targetPort HTTP/1.1\r\nHost: 127.0.0.1:$targetPort\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        out.flush()
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1))
        val status = reader.readLine()!!
        assertEquals("HTTP/1.1 200 Connection established", status)
        // Read the blank line ending headers so raw writes after this are clean
        assertEquals("", reader.readLine())
        return s
    }

    @Test
    fun connectTunnel_endToEndBytesFlow() {
        val tunneled = connectThroughProxy()
        val out = BufferedOutputStream(tunneled.getOutputStream())
        out.write("GET /hello.txt HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        out.flush()

        val input = BufferedInputStream(tunneled.getInputStream())
        val response = readAvailable(input, 2000)
        assertTrue(response.contains("HELLO-FROM-TARGET"))
        assertTrue(targetGotRequest.await(2, TimeUnit.SECONDS))
        synchronized(seenByTarget) {
            assertTrue(seenByTarget.contains("GET /hello.txt HTTP/1.1"))
        }
        tunneled.close()

        // Tunnel accounting returns to zero after close
        waitUntil { proxy.currentActiveTunnels == 0 }
        assertEquals(0, proxy.currentActiveTunnels)
        assertTrue(proxy.healthJson().contains("\"totalConnections\":1"))
    }

    @Test
    fun plainHttpForward_rewritesToOriginFormAndRelaysResponse() {
        val s = Socket("127.0.0.1", proxyPort)
        s.soTimeout = 5000
        val out = BufferedOutputStream(s.getOutputStream())
        out.write(
            "GET http://127.0.0.1:$targetPort/page.html HTTP/1.1\r\nHost: ignored.example\r\n\r\n"
                .toByteArray(StandardCharsets.US_ASCII),
        )
        out.flush()
        val body = readAvailable(BufferedInputStream(s.getInputStream()), 2000)
        assertTrue(body.contains("HELLO-FROM-TARGET"))
        assertTrue(targetGotRequest.await(2, TimeUnit.SECONDS))
        synchronized(seenByTarget) {
            // Absolute-form was rewritten to origin-form for the target
            assertTrue(seenByTarget.contains("GET /page.html HTTP/1.1"))
        }
        s.close()
    }

    @Test
    fun healthEndpoint_servedLocallyNotForwarded() {
        val s = Socket("127.0.0.1", proxyPort)
        s.soTimeout = 5000
        val out = BufferedOutputStream(s.getOutputStream())
        out.write("GET /meshgw/health HTTP/1.1\r\nHost: gw\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        out.flush()
        val body = readAvailable(BufferedInputStream(s.getInputStream()), 2000)
        assertTrue(body.contains("200 OK"))
        assertTrue(body.contains("\"running\":true"))
        s.close()
    }

    @Test
    fun disallowedClient_gets403() {
        val strict = ProxyServer(port = 0, isAllowed = { false })
        assertTrue(strict.start())
        try {
            val s = Socket("127.0.0.1", strict.boundPort)
            s.soTimeout = 5000
            s.getOutputStream().write("GET /meshgw/health HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            s.getOutputStream().flush()
            val body = readAvailable(BufferedInputStream(s.getInputStream()), 2000)
            assertTrue(body.contains("403 Forbidden"))
            s.close()
        } finally {
            strict.stop()
        }
    }

    @Test
    fun tunnelLimit_returns503WhenExhausted() {
        val tiny = ProxyServer(port = 0, maxTunnels = 1)
        assertTrue(tiny.start())
        try {
            // Occupy the single tunnel slot with a connection to a port that
            // accepts but never responds (our own target before we send GET).
            val first = Socket("127.0.0.1", tiny.boundPort)
            first.soTimeout = 5000
            val o = BufferedOutputStream(first.getOutputStream())
            o.write("CONNECT 127.0.0.1:$targetPort HTTP/1.1\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            o.flush()
            val r = BufferedReader(InputStreamReader(first.getInputStream(), StandardCharsets.ISO_8859_1))
            assertEquals("HTTP/1.1 200 Connection established", r.readLine())
            waitUntil { tiny.currentActiveTunnels == 1 }

            val second = Socket("127.0.0.1", tiny.boundPort)
            second.soTimeout = 5000
            val o2 = BufferedOutputStream(second.getOutputStream())
            o2.write("CONNECT 127.0.0.1:${targetPort + 1} HTTP/1.1\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            o2.flush()
            val r2 = BufferedReader(InputStreamReader(second.getInputStream(), StandardCharsets.ISO_8859_1))
            assertEquals("HTTP/1.1 503 Service Unavailable", r2.readLine())
            first.close()
            second.close()
        } finally {
            tiny.stop()
        }
    }

    @Test
    fun connectToUnreachableHost_returns502() {
        val s = Socket("127.0.0.1", proxyPort)
        s.soTimeout = 8000
        val out = BufferedOutputStream(s.getOutputStream())
        // Port 1 on loopback is closed in test envs -> immediate refusal
        out.write("CONNECT 127.0.0.1:1 HTTP/1.1\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        out.flush()
        val r = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1))
        assertEquals("HTTP/1.1 502 Bad Gateway", r.readLine())
        s.close()
    }

    private fun readAvailable(input: java.io.InputStream, timeoutMs: Long): String {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val b = input.read()
                if (b < 0) break
                sb.append(b.toChar())
            } else {
                if (sb.isNotEmpty()) break // got something and stream went quiet
                Thread.sleep(20)
            }
        }
        return sb.toString()
    }

    private fun waitUntil(timeoutMs: Long = 2000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }
}
