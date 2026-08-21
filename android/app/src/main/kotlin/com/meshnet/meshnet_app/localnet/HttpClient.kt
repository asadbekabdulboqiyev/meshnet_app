package com.meshnet.meshnet_app.localnet

import java.net.HttpURLConnection
import java.net.URL

/**
 * HttpClient - minimal HTTP GET client for peer-to-peer LocalNet transfers.
 *
 * Used over the Wi-Fi Direct group interface (direct TCP). Timeouts are
 * aggressive on purpose: mesh links appear and disappear constantly, a hung
 * connection must not stall sync jobs.
 */
class HttpClient(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) {

    companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        const val READ_TIMEOUT_MS = 15_000
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024 // 8 MB safety cap per response
    }

    /** GET text body, or null on any failure / non-200. */
    fun getText(ip: String, port: Int, path: String): String? {
        val bytes = getBytes(ip, port, path) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    /** GET raw body (max [maxBytes]), or null on any failure / non-200. */
    fun getBytes(ip: String, port: Int, path: String, maxBytes: Int = MAX_RESPONSE_BYTES): ByteArray? {
        if (ip.isBlank() || port !in 1..65535) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("http://$ip:$port$path").openConnection() as HttpURLConnection
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) return null
            val input = conn.inputStream
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                total += r
                if (total > maxBytes) return null
                out.write(buf, 0, r)
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
