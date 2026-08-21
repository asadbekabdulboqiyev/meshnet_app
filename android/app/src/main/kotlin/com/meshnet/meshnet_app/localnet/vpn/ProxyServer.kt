package com.meshnet.meshnet_app.localnet.vpn

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit

/**
 * ProxyServer - LocalNet Phase 5 zero-dependency HTTP forward proxy.
 *
 * This is the "Mesh VPN" data plane: a peer WITH internet runs this server;
 * peers without internet point proxy-aware apps at <gateway-ip>:<port>.
 *
 * Supported:
 *   - CONNECT host:port   -> raw TCP tunnel (covers all HTTPS traffic)
 *   - absolute-form HTTP   -> GET/POST http://host/path forwarded (plain http)
 *   - GET /meshgw/health   -> local JSON status + stats (not forwarded)
 *
 * Honest limits:
 *   - Plain-HTTP forwarding handles Content-Length bodies only; chunked
 *     request bodies are rejected with 411 (rare in practice).
 *   - No authentication: same mesh trust model as every LocalNet service.
 *     An optional [isAllowed] predicate lets the owner restrict clients.
 *   - This is NOT a transparent system-wide VPN: capturing other apps'
 *     traffic needs a VpnService + userspace TCP stack (documented as
 *     future work). Proxy-aware apps and browsers work today.
 */
class ProxyServer(
    private val port: Int = DEFAULT_PORT,
    private val isAllowed: (remoteAddress: String) -> Boolean = { true },
    private val maxTunnels: Int = MAX_TUNNELS,
    private val connectTimeoutMs: Int = 5_000,
    private val inactivityTimeoutMs: Int = 30_000,
) {

    companion object {
        const val DEFAULT_PORT = 8081
        const val MAX_TUNNELS = 24
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val PIPE_BUFFER = 16 * 1024
        private const val HEALTH_PATH = "/meshgw/health"
        private const val TAG = "ProxyServer"
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val acceptExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Proxy-accept").apply { isDaemon = true }
    }
    private val workerExecutor = ThreadPoolExecutor(
        4, maxTunnels * 2 + 8, 30L, TimeUnit.SECONDS,
        SynchronousQueue(),
    ) { r ->
        Thread(r, "Proxy-worker").apply { isDaemon = true }
    }

    // Stats (exposed via /meshgw/health)
    private val totalConnections = AtomicLong(0)
    private val activeTunnels = AtomicInteger(0)
    private val bytesToTarget = AtomicLong(0)
    private val bytesFromTarget = AtomicLong(0)
    private val deniedCount = AtomicLong(0)

    val isRunning: Boolean get() = running.get()
    val boundPort: Int get() = serverSocket?.localPort ?: -1
    val currentActiveTunnels: Int get() = activeTunnels.get()

    @Synchronized
    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
            ss.soTimeout = 0 // blocking accept forever
            serverSocket = ss
            running.set(true)
            acceptExecutor.submit { acceptLoop(ss) }
            Log.i(TAG, "ProxyServer started on port ${ss.localPort}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}")
            running.set(false)
            false
        }
    }

    @Synchronized
    fun stop() {
        if (!running.get()) return
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        workerExecutor.shutdown()
        try {
            if (!workerExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                workerExecutor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            workerExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        Log.i(TAG, "ProxyServer stopped")
    }

    /** Health snapshot for clients probing the gateway. */
    fun healthJson(): String =
        """{"running":$isRunning,"port":$boundPort,"activeTunnels":${activeTunnels.get()},""" +
            """"totalConnections":${totalConnections.get()},"bytesToTarget":${bytesToTarget.get()},""" +
            """"bytesFromTarget":${bytesFromTarget.get()},"denied":${deniedCount.get()}}"""

    // ---------------- Accept loop ----------------

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val client = try {
                ss.accept()
            } catch (_: IOException) {
                break // socket closed by stop()
            }
            totalConnections.incrementAndGet()
            try {
                client.soTimeout = inactivityTimeoutMs
                client.tcpNoDelay = true
            } catch (_: Exception) {
            }
            workerExecutor.execute { handleClient(client) }
        }
    }

    // ---------------- Client handling ----------------

    private fun handleClient(client: Socket) {
        try {
            val remote = client.inetAddress?.hostAddress ?: "unknown"
            if (!isAllowed(remote)) {
                deniedCount.incrementAndGet()
                writeSimpleResponse(client, "403 Forbidden", "mesh gateway: client not allowed")
                return
            }
            val input = BufferedInputStream(client.getInputStream(), PIPE_BUFFER)
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 3) {
                writeSimpleResponse(client, "400 Bad Request", "malformed request line")
                return
            }
            when {
                parts[0].equals("CONNECT", ignoreCase = true) -> handleConnect(client, input, parts[1])
                parts[1].startsWith("http://", ignoreCase = true) && !parts[1].contains(HEALTH_PATH) ->
                    handleForward(client, input, parts[0], parts[1], parts[2])
                parts[1].contains(HEALTH_PATH) -> handleHealth(client, input)
                else -> writeSimpleResponse(
                    client, "405 Method Not Allowed",
                    "only CONNECT, absolute-form HTTP or $HEALTH_PATH are supported",
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "client error: ${e.message}")
            try {
                client.close()
            } catch (_: IOException) {
            }
        }
    }

    /** CONNECT host:port -> 200 then bidirectional byte pipe. */
    private fun handleConnect(client: Socket, input: BufferedInputStream, authority: String) {
        val idx = authority.lastIndexOf(':')
        if (idx <= 0) {
            writeSimpleResponse(client, "400 Bad Request", "CONNECT requires host:port")
            return
        }
        val host = authority.substring(0, idx).removePrefix("[").removeSuffix("]")
        val targetPort = authority.substring(idx + 1).toIntOrNull()
        if (host.isBlank() || targetPort == null || targetPort !in 1..65535) {
            writeSimpleResponse(client, "400 Bad Request", "invalid CONNECT authority")
            return
        }
        // Drain request headers up to blank line (we do not need them)
        if (!drainHeaders(input)) {
            writeSimpleResponse(client, "400 Bad Request", "bad headers")
            return
        }
        if (activeTunnels.get() >= maxTunnels) {
            writeSimpleResponse(client, "503 Service Unavailable", "tunnel limit reached")
            return
        }
        val target = try {
            Socket().apply {
                connect(InetSocketAddress(host, targetPort), connectTimeoutMs)
                soTimeout = inactivityTimeoutMs
                tcpNoDelay = true
            }
        } catch (e: Exception) {
            Log.d(TAG, "connect to $host:$targetPort failed: ${e.message}")
            writeSimpleResponse(client, "502 Bad Gateway", "cannot reach $host:$targetPort")
            return
        }
        try {
            val out = BufferedOutputStream(client.getOutputStream(), PIPE_BUFFER)
            out.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            out.flush()
        } catch (e: IOException) {
            try {
                target.close(); client.close()
            } catch (_: IOException) {
            }
            return
        }
        activeTunnels.incrementAndGet()
        try {
            pipeBothWays(client, target)
        } finally {
            activeTunnels.decrementAndGet()
            try {
                target.close()
            } catch (_: IOException) {
            }
            try {
                client.close()
            } catch (_: IOException) {
            }
        }
    }

    /**
     * Absolute-form plain HTTP: rewrite to origin-form, forward with
     * Content-Length body, stream the response back untouched.
     */
    private fun handleForward(
        client: Socket,
        input: BufferedInputStream,
        method: String,
        absoluteUrl: String,
        httpVersion: String,
    ) {
        val uri = try {
            URI(absoluteUrl)
        } catch (_: Exception) {
            writeSimpleResponse(client, "400 Bad Request", "bad URL")
            return
        }
        val host = uri.host
        val targetPort = if (uri.port > 0) uri.port else 80
        if (host.isNullOrBlank()) {
            writeSimpleResponse(client, "400 Bad Request", "URL has no host")
            return
        }
        val headers = mutableMapOf<String, String>()
        var contentLength = 0L
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val sep = line.indexOf(':')
            if (sep <= 0) continue
            val name = line.substring(0, sep).trim()
            val value = line.substring(sep + 1).trim()
            headers[name.lowercase(Locale.US)] = value
            if (name.equals("content-length", ignoreCase = true)) {
                contentLength = value.toLongOrNull() ?: 0L
            }
        }
        if (headers.containsKey("transfer-encoding")) {
            writeSimpleResponse(client, "411 Length Required", "chunked request bodies not supported")
            return
        }
        val target = try {
            Socket().apply {
                connect(InetSocketAddress(host, targetPort), connectTimeoutMs)
                soTimeout = inactivityTimeoutMs
                tcpNoDelay = true
            }
        } catch (e: Exception) {
            writeSimpleResponse(client, "502 Bad Gateway", "cannot reach $host:$targetPort")
            return
        }
        try {
            val tOut = BufferedOutputStream(target.getOutputStream(), PIPE_BUFFER)
            val path = if (uri.rawQuery != null) "${uri.rawPath}?${uri.rawQuery}" else uri.rawPath.orEmpty().ifEmpty { "/" }
            tOut.write("$method $path $httpVersion\r\n".toByteArray(StandardCharsets.US_ASCII))
            if (!headers.containsKey("host")) {
                tOut.write("Host: $host${if (targetPort != 80) ":$targetPort" else ""}\r\n".toByteArray(StandardCharsets.US_ASCII))
            }
            if (contentLength > 0) {
                tOut.write("Content-Length: $contentLength\r\n".toByteArray(StandardCharsets.US_ASCII))
            }
            tOut.write("Connection: close\r\n".toByteArray(StandardCharsets.US_ASCII))
            tOut.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
            var remaining = contentLength
            val buf = ByteArray(PIPE_BUFFER)
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                tOut.write(buf, 0, n)
                remaining -= n
            }
            tOut.flush()
            // Stream response back until the target closes (Connection: close)
            val cOut = BufferedOutputStream(client.getOutputStream(), PIPE_BUFFER)
            val respBuf = ByteArray(PIPE_BUFFER)
            while (true) {
                val n = target.getInputStream().read(respBuf)
                if (n < 0) break
                cOut.write(respBuf, 0, n)
                bytesFromTarget.addAndGet(n.toLong())
            }
            cOut.flush()
        } catch (e: Exception) {
            Log.d(TAG, "forward error: ${e.message}")
        } finally {
            try {
                target.close()
            } catch (_: IOException) {
            }
            try {
                client.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun handleHealth(client: Socket, input: BufferedInputStream) {
        try {
            drainHeaders(input)
            val body = healthJson()
            val out = client.getOutputStream()
            val payload = (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n" +
                    "Connection: close\r\n\r\n$body"
                ).toByteArray(StandardCharsets.UTF_8)
            out.write(payload)
            out.flush()
        } catch (_: Exception) {
        } finally {
            try {
                client.close()
            } catch (_: IOException) {
            }
        }
    }

    // ---------------- Plumbing ----------------

    /** Pipe both directions; returns when either side closes. */
    private fun pipeBothWays(a: Socket, b: Socket) {
        val aToB = Thread {
            val n = pipe(a.getInputStream(), b.getOutputStream(), bytesToTarget)
            if (n >= 0) {
                // Half-close so the target sees EOF while we still receive
                try {
                    b.shutdownOutput()
                } catch (_: IOException) {
                }
            }
        }
        aToB.name = "Proxy-pipe-out"
        aToB.isDaemon = true
        aToB.start()
        val n = pipe(b.getInputStream(), a.getOutputStream(), bytesFromTarget)
        if (n >= 0) {
            try {
                a.shutdownOutput()
            } catch (_: IOException) {
            }
        }
        // Give the opposite direction a moment to drain, then force close
        aToB.join(1000)
    }

    /** Returns total bytes piped, or -1 on immediate EOF/error. */
    private fun pipe(input: java.io.InputStream, output: java.io.OutputStream, counter: AtomicLong): Int {
        val buf = ByteArray(PIPE_BUFFER)
        var total = 0
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                counter.addAndGet(n.toLong())
                total += n
            }
            output.flush()
        } catch (_: IOException) {
            // timeout or reset — tunnel ends, that is normal lifecycle
        }
        return if (total == 0 && running.get()) -1 else total
    }

    /** Reads header lines until the blank line; false on EOF/oversize. */
    private fun drainHeaders(input: BufferedInputStream): Boolean {
        var total = 0
        while (true) {
            val line = readLine(input) ?: return false
            total += line.length + 2
            if (line.isEmpty()) return true
            if (total > MAX_HEADER_BYTES) return false
        }
    }

    /** Reads one CRLF/LF terminated line as ISO-8859-1 (HTTP safe). */
    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder(64)
        while (sb.length <= MAX_HEADER_BYTES) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        return null
    }

    private fun writeSimpleResponse(client: Socket, status: String, message: String) {
        try {
            val body = message.toByteArray(StandardCharsets.UTF_8)
            val head = (
                "HTTP/1.1 $status\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(StandardCharsets.US_ASCII)
            client.getOutputStream().write(head + body)
            client.getOutputStream().flush()
        } catch (_: IOException) {
        } finally {
            try {
                client.close()
            } catch (_: IOException) {
            }
        }
    }
}
