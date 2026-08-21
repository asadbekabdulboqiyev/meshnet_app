package com.meshnet.meshnet_app.localnet

import java.util.concurrent.ConcurrentHashMap

/**
 * DnsRegistry - LocalNet decentralized DNS (Phase 1).
 *
 * No central server: every node keeps its own view of hostname -> deviceId
 * bindings learned from DNS_ANNOUNCE / DNS_QUERY / DNS_RESPONSE mesh frames.
 *
 * Conflict rule (deterministic on all nodes):
 *   1. A binding is owned by the FIRST deviceId that announced it
 *      (earliest firstSeenMs wins).
 *   2. Tie (same ms): lexicographically smallest deviceId wins.
 *   3. The rightful owner can re-announce to refresh; anyone else is rejected.
 *
 * Known limitation (honest): announcements are not signed yet, so a node
 * that joins before the real owner could claim its name. Cryptographic
 * ownership proof (signature with identity key) is planned for Phase 2.
 *
 * Payload wire format (UTF-8, '|' separated):
 *   ANNOUNCE: "hostname|firstRegisteredMs"                       (legacy)
 *             "hostname|firstRegisteredMs|httpPort|ipAddress"    (Phase 2+)
 *   QUERY:    "hostname"
 *   RESPONSE: "hostname|deviceId|found(0|1)|lastSeenMs"
 */
class DnsRegistry(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    companion object {
        const val HOSTNAME_MAX_LEN = 63
        const val DEFAULT_TTL_MS = 10L * 60 * 1000 // 10 min without refresh -> expired
        const val MAX_HOSTS = 256

        /** RFC 1123 label: a-z, 0-9, '-', 1-63 chars, no leading/trailing '-'. */
        fun isValidHostname(name: String): Boolean {
            if (name.isEmpty() || name.length > HOSTNAME_MAX_LEN) return false
            if (!name[0].isLetterOrDigit() || !name[name.length - 1].isLetterOrDigit()) return false
            return name.all { it.isLetterOrDigit() || it == '-' }
        }

        /** Full local domain for a host, e.g. "asadbek.mesh". */
        fun toFqdn(hostname: String) = "$hostname.mesh"

        /** Parse "hostname|deviceId|found|lastSeenMs" response payload. */
        fun parseResponse(payload: String): ResponseData? {
            val parts = payload.split("|")
            if (parts.size != 4) return null
            val found = when (parts[2]) {
                "1" -> true
                "0" -> false
                else -> return null
            }
            val lastSeen = parts[3].toLongOrNull() ?: return null
            return ResponseData(parts[0], parts[1], found, lastSeen)
        }

        /** Parse announce payload (2-part legacy or 4-part with endpoint). */
        fun parseAnnounce(payload: String): AnnounceData? {
            val parts = payload.split("|")
            return when (parts.size) {
                2 -> {
                    val ts = parts[1].toLongOrNull() ?: return null
                    AnnounceData(parts[0], ts, -1, "")
                }
                4 -> {
                    val ts = parts[1].toLongOrNull() ?: return null
                    val port = parts[2].toIntOrNull() ?: return null
                    if (port < -1 || port > 65535) return null
                    AnnounceData(parts[0], ts, port, parts[3])
                }
                else -> null
            }
        }
    }

    data class HostEntry(
        val hostname: String,
        val deviceId: String,
        val displayName: String,
        val firstRegisteredMs: Long,
        var lastRefreshedMs: Long,
        val httpPort: Int = -1,
        val ipAddress: String = "",
    ) {
        fun isExpired(now: Long, ttlMs: Long = DEFAULT_TTL_MS): Boolean =
            now - lastRefreshedMs > ttlMs

        /** True when this host announced a reachable HTTP endpoint. */
        val hasEndpoint: Boolean get() = httpPort > 0 && ipAddress.isNotBlank()
    }

    data class ResponseData(
        val hostname: String,
        val deviceId: String,
        val found: Boolean,
        val lastSeenMs: Long,
    )

    data class AnnounceData(
        val hostname: String,
        val firstRegisteredMs: Long,
        val httpPort: Int,
        val ipAddress: String,
    )

    private val hosts = ConcurrentHashMap<String, HostEntry>()

    val size: Int get() = hosts.size

    // ---------------- Local registration ----------------

    /**
     * Register OUR own hostname. Returns true if accepted.
     * Own device always keeps priority over remote claims made earlier
     * or at the same logical time (owner re-announce path).
     */
    fun registerSelf(hostname: String, selfDeviceId: String, displayName: String): Boolean {
        if (!isValidHostname(hostname)) return false
        val now = nowMs()
        val existing = hosts[hostname]
        if (existing != null && existing.deviceId != selfDeviceId && existing.firstRegisteredMs < now) {
            // Remote node claimed this name strictly earlier and we are not
            // the owner -> conflict, keep deterministic winner.
            return false
        }
        hosts[hostname] = HostEntry(
            hostname = hostname,
            deviceId = selfDeviceId,
            displayName = displayName,
            firstRegisteredMs = existing?.firstRegisteredMs?.coerceAtMost(now) ?: now,
            lastRefreshedMs = now,
        )
        return true
    }

    /** Wire payload for our announce frame. */
    fun buildAnnouncePayload(hostname: String): String? {
        val entry = hosts[hostname] ?: return null
        return "${entry.hostname}|${entry.firstRegisteredMs}"
    }

    // ---------------- Remote learning ----------------

    /**
     * Learn from a received DNS_ANNOUNCE frame.
     * Returns true if the entry was newly added or refreshed.
     */
    fun handleAnnounce(
        hostname: String,
        senderDeviceId: String,
        senderDisplayName: String,
        claimedFirstRegisteredMs: Long,
        httpPort: Int = -1,
        ipAddress: String = "",
    ): Boolean {
        if (!isValidHostname(hostname)) return false
        val now = nowMs()
        val existing = hosts[hostname]
        if (existing != null) {
            if (existing.deviceId == senderDeviceId) {
                existing.lastRefreshedMs = now
                // Endpoint may change (new Wi-Fi Direct group, DHCP lease)
                if (httpPort > 0 && ipAddress.isNotBlank()) {
                    hosts[hostname] = existing.copy(httpPort = httpPort, ipAddress = ipAddress)
                }
                return true
            }
            // Conflict: earliest registration wins; tie -> smaller deviceId.
            val claimOlder = claimedFirstRegisteredMs < existing.firstRegisteredMs ||
                (claimedFirstRegisteredMs == existing.firstRegisteredMs && senderDeviceId < existing.deviceId)
            if (!claimOlder) return false
        }
        if (hosts.size >= MAX_HOSTS && existing == null) {
            evictOldest()
        }
        hosts[hostname] = HostEntry(
            hostname = hostname,
            deviceId = senderDeviceId,
            displayName = senderDisplayName,
            firstRegisteredMs = claimedFirstRegisteredMs,
            lastRefreshedMs = now,
            httpPort = httpPort,
            ipAddress = ipAddress,
        )
        return true
    }

    /** Build answer payload for a DNS_QUERY, or null if we don't know the host. */
    fun buildResponsePayload(hostname: String): String? {
        val entry = resolve(hostname) ?: return null
        return "$hostname|${entry.deviceId}|1|${entry.lastRefreshedMs}"
    }

    /** Learn from a received DNS_RESPONSE frame (multi-hop resolution). */
    fun handleResponse(data: ResponseData): Boolean {
        if (!data.found) return false
        if (!isValidHostname(data.hostname)) return false
        val now = nowMs()
        val existing = hosts[data.hostname]
        if (existing != null && existing.deviceId != data.deviceId) {
            val claimOlder = data.lastSeenMs < existing.firstRegisteredMs ||
                (data.lastSeenMs == existing.firstRegisteredMs && data.deviceId < existing.deviceId)
            if (!claimOlder) return false
        }
        if (hosts.size >= MAX_HOSTS && existing == null) {
            evictOldest()
        }
        hosts[data.hostname] = HostEntry(
            hostname = data.hostname,
            deviceId = data.deviceId,
            displayName = "",
            firstRegisteredMs = data.lastSeenMs,
            lastRefreshedMs = now,
        )
        return true
    }

    // ---------------- Lookup ----------------

    /** Resolve hostname -> entry, ignoring expired records. */
    fun resolve(hostname: String): HostEntry? {
        val entry = hosts[hostname] ?: return null
        if (entry.isExpired(nowMs())) {
            hosts.remove(hostname)
            return null
        }
        return entry
    }

    /** Reverse lookup: deviceId -> hostname (or null). */
    fun reverseLookup(deviceId: String): HostEntry? =
        hosts.values.firstOrNull { it.deviceId == deviceId && !it.isExpired(nowMs()) }

    /** All live entries sorted by hostname. */
    fun snapshot(): List<HostEntry> {
        val now = nowMs()
        return hosts.values
            .filter { !it.isExpired(now) }
            .sortedBy { it.hostname }
    }

    /** Drop expired entries; returns how many were removed. */
    fun expire(): Int {
        val now = nowMs()
        val dead = hosts.values.filter { it.isExpired(now) }
        dead.forEach { hosts.remove(it.hostname) }
        return dead.size
    }

    fun clear() = hosts.clear()

    private fun evictOldest() {
        hosts.values.minByOrNull { it.lastRefreshedMs }?.let { hosts.remove(it.hostname) }
    }
}
