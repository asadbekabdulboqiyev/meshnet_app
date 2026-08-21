package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.localnet.vpn.GatewayRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 unit testlari: gateway presence registry — learn, refresh,
 * TTL expiry, stale announce handling, payload parsing.
 */
class GatewayRegistryTest {

    private var now = 1_000_000L
    private val registry = GatewayRegistry(nowMs = { now })

    private fun announce(
        hostname: String = "gw-node",
        ip: String = "192.168.49.2",
        port: Int = 8081,
        startedAt: Long = 999_000L,
    ) = GatewayRegistry.AnnounceData(hostname, ip, port, startedAt)

    @Test
    fun handleAnnounce_learnsNewGateway() {
        assertTrue(registry.handleAnnounce("device-a", announce()))
        assertEquals(1, registry.size)
        val e = registry.resolve("device-a")!!
        assertEquals("gw-node", e.hostname)
        assertEquals("192.168.49.2", e.ipAddress)
        assertEquals(8081, e.proxyPort)
    }

    @Test
    fun handleAnnounce_refreshesExistingEntry() {
        registry.handleAnnounce("device-a", announce())
        now += 30_000 // one announce period later
        assertTrue(registry.handleAnnounce("device-a", announce()))
        val e = registry.resolve("device-a")!!
        assertEquals(now, e.lastSeenMs)
        assertEquals(1, registry.size) // no duplicate entry
    }

    @Test
    fun handleAnnounce_staleRestartKeepsOriginalStartTime() {
        registry.handleAnnounce("device-a", announce(startedAt = 5_000))
        // An in-flight pre-restart announce (startedAt 4s) arrives late:
        // uptime must never rewind backwards
        registry.handleAnnounce("device-a", announce(startedAt = 4_000))
        val e = registry.resolve("device-a")!!
        assertEquals(5_000L, e.startedAtMs) // delayed old frame ignored
        assertEquals(now, e.lastSeenMs) // but contact info is refreshed
    }

    @Test
    fun expire_removesOnlyStaleEntries() {
        registry.handleAnnounce("device-a", announce())
        now += GatewayRegistry.DEFAULT_TTL_MS - 1
        assertEquals(0, registry.expire())
        assertEquals(1, registry.size)

        now += 10_000 // past TTL
        assertEquals(1, registry.expire())
        assertEquals(0, registry.size)
    }

    @Test
    fun snapshot_sortedByHostname() {
        registry.handleAnnounce("d2", announce(hostname = "zeta"))
        registry.handleAnnounce("d1", announce(hostname = "alpha"))
        val names = registry.snapshot().map { it.hostname }
        assertEquals(listOf("alpha", "zeta"), names)
    }

    @Test
    fun parseAnnouncePayload_validAndInvalid() {
        val ok = GatewayRegistry.parseAnnouncePayload("node|192.168.1.5|8081|12345")
        assertNotNull(ok)
        assertEquals("node", ok!!.hostname)
        assertEquals(8081, ok.proxyPort)

        assertNull(GatewayRegistry.parseAnnouncePayload("node|192.168.1.5|8081")) // 3 parts
        assertNull(GatewayRegistry.parseAnnouncePayload("node||8081|12345")) // blank ip
        assertNull(GatewayRegistry.parseAnnouncePayload("node|ip|notaport|12345"))
        assertNull(GatewayRegistry.parseAnnouncePayload("node|ip|0|12345")) // port must be >= 1
        assertNull(GatewayRegistry.parseAnnouncePayload("node|ip|70000|12345"))
        assertNull(GatewayRegistry.parseAnnouncePayload("node|ip|8081|notats"))
    }

    @Test
    fun maxGateways_evictsOldestSeen() {
        repeat(GatewayRegistry.MAX_GATEWAYS) {
            registry.handleAnnounce("dev-$it", announce(hostname = "h$it"))
            now += 1
        }
        assertEquals(GatewayRegistry.MAX_GATEWAYS, registry.size)
        // One more -> oldest (dev-0) evicted
        registry.handleAnnounce("dev-new", announce(hostname = "new"))
        assertNull(registry.resolve("dev-0"))
        assertNotNull(registry.resolve("dev-new"))
    }

    @Test
    fun blankDeviceIdRejected() {
        assertFalse(registry.handleAnnounce("", announce()))
        assertEquals(0, registry.size)
    }
}
