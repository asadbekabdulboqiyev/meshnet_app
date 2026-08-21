package com.meshnet.meshnet_app.localnet

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import com.meshnet.meshnet_app.localnet.chunk.ChunkStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * LocalHttpServer - LocalNet Phase 1 offline web server.
 *
 * Zero-dependency HTTP/1.1 server on a raw ServerSocket. Binds to all
 * interfaces so it is reachable over the Wi-Fi Direct group interface
 * (and loopback for tests).
 *
 * HONEST LIMITATION: this serves DIRECT TCP connections only. A peer must
 * be in the same Wi-Fi Direct group (or BLE-gatewayed later) to reach it.
 * HTTP-over-BLE tunneling is a later phase; BLE-only links are far too
 * slow (~50-100 kbps) for real browsing.
 *
 * Endpoints:
 *   GET /                -> HTML device page
 *   GET /info            -> JSON device info
 *   GET /dns             -> JSON list of known mesh hosts
 *   GET /files           -> shared file ids (one per line)          [Phase 2]
 *   GET /manifest/<id>   -> LNMANIFEST text for a shared file       [Phase 2]
 *   GET /chunk/<hash>    -> raw chunk bytes (sha256-verified)       [Phase 2]
 *   GET /collab/board/<room> -> LNBOARD snapshot                    [Phase 3]
 *   GET /collab/doc/<id>     -> LNDOC snapshot                      [Phase 3]
 *   GET /collab/polls        -> LNPOLLS snapshot                    [Phase 3]
 */
class LocalHttpServer(
    private val port: Int = DEFAULT_PORT,
    private val contentProvider: ContentProvider,
    private val fileProvider: FileProvider? = null,
    private val collabProvider: CollabProvider? = null,
) {

    companion object {
        const val DEFAULT_PORT = 8080
        private const val BACKLOG = 16
        private const val SO_TIMEOUT_MS = 10_000
        private const val MAX_THREADS = 8
        private const val MAX_HEADER_LINES = 100
        private const val MAX_BODY_BYTES = 64 * 1024

        fun escapeJson(s: String): String = buildString {
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    /** Supplies dynamic content; kept Android-free for unit testing. */
    interface ContentProvider {
        fun deviceName(): String
        fun deviceId(): String
        fun knownHostsJson(): String
    }

    /** Phase 2: file sharing content (manifests + chunks). */
    interface FileProvider {
        fun sharedFileIds(): List<String>
        fun manifestText(fileId: String): String?
        fun chunkData(hash: String): ByteArray?
    }

    /** Phase 3: collaboration state snapshots for late joiners. */
    interface CollabProvider {
        fun boardSnapshot(roomId: String): String?
        fun docSnapshot(docId: String): String?
        fun pollsSnapshot(): String
    }

    data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
    )

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newFixedThreadPool(MAX_THREADS)
    private val activeConnections = AtomicInteger(0)

    val isRunning: Boolean get() = running.get()
    val boundPort: Int get() = serverSocket?.localPort ?: -1

    @Synchronized
    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val ss = ServerSocket(port, BACKLOG)
            ss.reuseAddress = true
            serverSocket = ss
            running.set(true)
            Thread({
                acceptLoop(ss)
            }, "LocalHttpServer-accept").apply { isDaemon = true }.start()
            true
        } catch (_: Exception) {
            running.set(false)
            false
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        executor.shutdownNow()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val client = try {
                ss.accept()
            } catch (_: Exception) {
                break // socket closed while stopping
            }
            if (!running.get()) {
                try { client.close() } catch (_: Exception) {}
                break
            }
            executor.submit { handleConnection(client) }
        }
    }

    private fun handleConnection(socket: Socket) {
        activeConnections.incrementAndGet()
        try {
            socket.soTimeout = SO_TIMEOUT_MS
            socket.getInputStream().use { input ->
                socket.getOutputStream().use { output ->
                    val reader = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1))
                    val request = parseRequest(reader) ?: run {
                        writeResponse(output, 400, "text/plain", "Bad Request".toByteArray())
                        return
                    }
                    val response = route(request)
                    writeResponse(output, response.first, response.second, response.third)
                }
            }
        } catch (_: Exception) {
            // client timeout / reset — normal under flaky mesh links
        } finally {
            activeConnections.decrementAndGet()
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun writeResponse(out: java.io.OutputStream, code: Int, contentType: String, body: ByteArray) {
        val reason = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; else -> "Error"
        }
        val header = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    internal fun parseRequest(reader: BufferedReader): Request? {
        val requestLine = reader.readLine() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        if (method != "GET" && method != "HEAD") return null
        val rawPath = parts[1]
        val path = rawPath.substringBefore("?")
        val headers = HashMap<String, String>()
        var count = 0
        while (count < MAX_HEADER_LINES) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            count++
        }
        // Drain small bodies defensively (we never use them in Phase 1)
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength in 1..MAX_BODY_BYTES) {
            val body = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val r = reader.read(body, read, contentLength - read)
                if (r < 0) break
                read += r
            }
        }
        return Request(method, path, headers)
    }

    internal fun route(request: Request): Triple<Int, String, ByteArray> {
        return when {
            request.path == "/" || request.path == "/index.html" ->
                Triple(200, "text/html; charset=utf-8", renderIndex().toByteArray(Charsets.UTF_8))
            request.path == "/info" ->
                Triple(200, "application/json", renderInfo().toByteArray(Charsets.UTF_8))
            request.path == "/dns" ->
                Triple(200, "application/json", contentProvider.knownHostsJson().toByteArray(Charsets.UTF_8))
            request.path == "/files" -> routeFiles()
            request.path.startsWith("/manifest/") -> routeManifest(request.path.removePrefix("/manifest/"))
            request.path.startsWith("/chunk/") -> routeChunk(request.path.removePrefix("/chunk/"))
            request.path == "/collab/polls" -> routeCollabPolls()
            request.path.startsWith("/collab/board/") -> routeCollabBoard(request.path.removePrefix("/collab/board/"))
            request.path.startsWith("/collab/doc/") -> routeCollabDoc(request.path.removePrefix("/collab/doc/"))
            else ->
                Triple(404, "text/plain", "Not Found".toByteArray())
        }
    }

    private fun routeFiles(): Triple<Int, String, ByteArray> {
        val fp = fileProvider ?: return Triple(404, "text/plain", "Not Found".toByteArray())
        val body = fp.sharedFileIds().joinToString("\n", postfix = if (fp.sharedFileIds().isEmpty()) "" else "\n")
        return Triple(200, "text/plain; charset=utf-8", body.toByteArray(Charsets.UTF_8))
    }

    private fun routeManifest(fileId: String): Triple<Int, String, ByteArray> {
        val fp = fileProvider ?: return Triple(404, "text/plain", "Not Found".toByteArray())
        if (fileId.length !in 16..64 || !fileId.all { it.isDigit() || it in 'a'..'f' }) {
            return Triple(400, "text/plain", "Bad file id".toByteArray())
        }
        val text = fp.manifestText(fileId)
            ?: return Triple(404, "text/plain", "Not Found".toByteArray())
        return Triple(200, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))
    }

    private fun routeChunk(hash: String): Triple<Int, String, ByteArray> {
        val fp = fileProvider ?: return Triple(404, "text/plain", "Not Found".toByteArray())
        if (!ChunkStore.isValidHash(hash)) {
            return Triple(400, "text/plain", "Bad chunk hash".toByteArray())
        }
        val data = fp.chunkData(hash)
            ?: return Triple(404, "text/plain", "Not Found".toByteArray())
        return Triple(200, "application/octet-stream", data)
    }

    private fun routeCollabBoard(roomId: String): Triple<Int, String, ByteArray> {
        val cp = collabProvider ?: return Triple(404, "text/plain", "Not Found".toByteArray())
        if (roomId.isEmpty() || roomId.length > 32 || !roomId.all { it.isLowerCase() || it.isDigit() || it == '-' }) {
            return Triple(400, "text/plain", "Bad room id".toByteArray())
        }
        val text = cp.boardSnapshot(roomId)
            ?: return Triple(404, "text/plain", "Not Found".toByteArray())
        return Triple(200, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))
    }

    private fun routeCollabDoc(docId: String): Triple<Int, String, ByteArray> {
        val cp = collabProvider ?: return Triple(404, "text/plain", "Not Found".toByteArray())
        if (docId.isEmpty() || docId.length > 32 || !docId.all { it.isLowerCase() || it.isDigit() || it == '-' }) {
            return Triple(400, "text/plain", "Bad doc id".toByteArray())
        }
        val text = cp.docSnapshot(docId)
            ?: return Triple(404, "text/plain", "Not Found".toByteArray())
        return Triple(200, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))
    }

    private fun routeCollabPolls(): Triple<Int, String, ByteArray> {
        val cp = collabProvider ?: return Triple(404, "text/plain", "Not Found".toByteArray())
        return Triple(200, "text/plain; charset=utf-8", cp.pollsSnapshot().toByteArray(Charsets.UTF_8))
    }

    private fun renderIndex(): String {
        val name = escapeJson(contentProvider.deviceName()).replace("\\u0022", "")
        return """
<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(name)} - LocalNet</title>
<style>
body{font-family:monospace;background:#0a0f0a;color:#7fff7f;margin:2rem}
h1{border-bottom:1px solid #2f5f2f;padding-bottom:.5rem}
code{color:#bfffbf}
a{color:#7fdfff}
</style></head><body>
<h1>LocalNet Node</h1>
<p>Device: <strong>${escapeHtml(name)}</strong></p>
<p>ID: <code>${escapeHtml(contentProvider.deviceId())}</code></p>
<p>This page is served from the device itself - no internet required.</p>
<p><a href="/dns">Known mesh hosts</a> | <a href="/info">JSON info</a></p>
</body></html>""".trim()
    }

    private fun renderInfo(): String {
        return "{\"deviceName\":\"${escapeJson(contentProvider.deviceName())}\"," +
            "\"deviceId\":\"${escapeJson(contentProvider.deviceId())}\"," +
            "\"service\":\"LocalNet\",\"version\":1}"
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
