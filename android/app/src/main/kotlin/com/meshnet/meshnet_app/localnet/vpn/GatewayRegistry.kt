package com.meshnet.meshnet_app.localnet.vpn

import java.util.concurrent.ConcurrentHashMap

/**
 * GatewayRegistry - LocalNet Phase 5 internet gateway presence tracker.
 *
 * Every node keeps its own view of which mesh peers currently share their
 * internet connection, learned from VPN_GW_ANNOUNCE frames (flooded every
 * ~30s while a gateway is active). Entries expire quickly (gateways are
 * ephemeral: airplane mode, tethering off) so stale gateways disappear.
 *
 * Payload wire format (UTF-8, '|' separated):
 *   "hostname|ipAddress|proxyPort|startedAtMs"
 *
 * Honest limits: announcements are not signed (same trust model as DNS);
 * any node can claim to be a gateway. Clients verify reachability with an
 * HTTP health probe before use.
 */
class GatewayRegistry(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    companion object {
        /** A gateway that stops announcing disappears after this. */
        const val DEFAULT_TTL_MS = 120L * 1000
        const val MAX_GATEWAYS = 64

        fun parseAnnouncePayload(payload: String): AnnounceData? {
            val parts = payload.split("|")
            if (parts.size != 4) return null
            val port = parts[2].toIntOrNull() ?: return null
            if (port < 1 || port > 65535) return null
            val startedAt = parts[3].toLongOrNull() ?: return null
            if (parts[1].isBlank()) return null
            return AnnounceData(hostname = parts[0], ipAddress = parts[1], proxyPort = port, startedAtMs = startedAt)
        }
    }

    data class AnnounceData(
        val hostname: String,
        val ipAddress: String,
        val proxyPort: Int,
        val startedAtMs: Long,
    )

    data class GatewayEntry(
        val deviceId: String,
        val hostname: String,
        val ipAddress: String,
        val proxyPort: Int,
        val startedAtMs: Long,
        var lastSeenMs: Long,
    ) {
        fun isExpired(now: Long, ttlMs: Long = DEFAULT_TTL_MS): Boolean =
            now - lastSeenMs > ttlMs
    }

    private val gateways = ConcurrentHashMap<String, GatewayEntry>()

    val size: Int get() = gateways.size

    /**
     * Learn from a received VPN_GW_ANNOUNCE frame.
     * Returns true when the entry is new or was refreshed.
     */
    fun handleAnnounce(
        senderDeviceId: String,
        data: AnnounceData,
    ): Boolean {
        if (senderDeviceId.isBlank()) return false
        val now = nowMs()
        if (gateways.size >= MAX_GATEWAYS && !gateways.containsKey(senderDeviceId)) {
            evictOldest()
        }
        val existing = gateways[senderDeviceId]
        if (existing != null && data.startedAtMs < existing.startedAtMs) {
            // Stale announce from before a gateway restart: refresh contact
            // info but keep the original start time.
            existing.lastSeenMs = now
            return true
        }
        gateways[senderDeviceId] = GatewayEntry(
            deviceId = senderDeviceId,
            hostname = data.hostname,
            ipAddress = data.ipAddress,
            proxyPort = data.proxyPort,
            startedAtMs = data.startedAtMs,
            lastSeenMs = now,
        )
        return true
    }

    fun resolve(deviceId: String): GatewayEntry? = gateways[deviceId]

    /** Live entries sorted by hostname for stable UI ordering. */
    fun snapshot(): List<GatewayEntry> =
        gateways.values.sortedBy { it.hostname }

    /** Remove expired entries; returns how many were dropped. */
    fun expire(): Int {
        val now = nowMs()
        var removed = 0
        gateways.entries.removeIf { (_, e) ->
            if (e.isExpired(now)) {
                removed++
                true
            } else {
                false
            }
        }
        return removed
    }

    fun clear() = gateways.clear()

    private fun evictOldest() {
        gateways.entries.minByOrNull { it.value.lastSeenMs }?.let { gateways.remove(it.key) }
    }
}
