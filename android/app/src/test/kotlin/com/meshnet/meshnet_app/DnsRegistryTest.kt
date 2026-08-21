package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.localnet.DnsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DnsRegistry testlari: hostname validatsiya, o'z nomini ro'yxatga olish,
 * masofaviy announce, konflikt hal qilish (deterministik), TTL va lookup.
 */
class DnsRegistryTest {

    private val ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val ID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

    private fun registry(vararg times: Long): DnsRegistry {
        var i = 0
        return DnsRegistry { if (i < times.size) times[i++] else times.last() }
    }

    // ---------------- Hostname validation ----------------

    @Test
    fun validHostnames() {
        assertTrue(DnsRegistry.isValidHostname("asadbek"))
        assertTrue(DnsRegistry.isValidHostname("node-1"))
        assertTrue(DnsRegistry.isValidHostname("a"))
        assertTrue(DnsRegistry.isValidHostname("abc123def"))
        assertTrue(DnsRegistry.isValidHostname("a".repeat(63)))
    }

    @Test
    fun invalidHostnames() {
        assertFalse(DnsRegistry.isValidHostname(""))
        assertFalse(DnsRegistry.isValidHostname("-leading"))
        assertFalse(DnsRegistry.isValidHostname("trailing-"))
        assertFalse(DnsRegistry.isValidHostname("has space"))
        assertFalse(DnsRegistry.isValidHostname("dot.name"))
        assertFalse(DnsRegistry.isValidHostname("uzbekcha'apos"))
        assertFalse(DnsRegistry.isValidHostname("a".repeat(64)))
    }

    @Test
    fun fqdnFormat() {
        assertEquals("asadbek.mesh", DnsRegistry.toFqdn("asadbek"))
    }

    // ---------------- Self registration ----------------

    @Test
    fun registerSelfAcceptsValidName() {
        val dns = registry(1000)
        assertTrue(dns.registerSelf("asadbek", ID_A, "Asadbek"))
        assertEquals(ID_A, dns.resolve("asadbek")?.deviceId)
        assertEquals(1, dns.size)
    }

    @Test
    fun registerSelfRejectsInvalidName() {
        val dns = registry(1000)
        assertFalse(dns.registerSelf("bad name!", ID_A, "Asadbek"))
        assertNull(dns.resolve("bad name!"))
    }

    @Test
    fun selfReRegistrationRefreshes() {
        val dns = registry(1000, 2000)
        assertTrue(dns.registerSelf("asadbek", ID_A, "Asadbek"))
        assertTrue(dns.registerSelf("asadbek", ID_A, "Asadbek"))
        assertEquals(1, dns.size)
    }

    @Test
    fun buildAnnouncePayloadContainsFirstRegisteredTime() {
        val dns = registry(1000)
        dns.registerSelf("asadbek", ID_A, "Asadbek")
        assertEquals("asadbek|1000", dns.buildAnnouncePayload("asadbek"))
        assertNull(dns.buildAnnouncePayload("unknown"))
    }

    // ---------------- Remote announce ----------------

    @Test
    fun remoteAnnounceLearned() {
        val dns = registry(5000)
        assertTrue(dns.handleAnnounce("node-b", ID_B, "B", 3000))
        val entry = dns.resolve("node-b")
        assertNotNull(entry)
        assertEquals(ID_B, entry?.deviceId)
        assertEquals(3000L, entry?.firstRegisteredMs)
    }

    @Test
    fun remoteAnnounceRefreshesSameOwner() {
        val dns = registry(5000, 6000)
        assertTrue(dns.handleAnnounce("node-b", ID_B, "B", 3000))
        assertTrue(dns.handleAnnounce("node-b", ID_B, "B", 3000))
        assertEquals(1, dns.size)
    }

    @Test
    fun conflictEarliestClaimWins() {
        val dns = registry(5000)
        // B claims first (registered at 3000)
        assertTrue(dns.handleAnnounce("shared", ID_B, "B", 3000))
        // A claims later registration time (4000) -> rejected
        assertFalse(dns.handleAnnounce("shared", ID_A, "A", 4000))
        assertEquals(ID_B, dns.resolve("shared")?.deviceId)
    }

    @Test
    fun conflictEarlierClaimSteals() {
        val dns = registry(5000)
        // A registered first locally-known claim at 4000
        assertTrue(dns.handleAnnounce("shared", ID_A, "A", 4000))
        // B has strictly earlier claim (3000) -> wins deterministically
        assertTrue(dns.handleAnnounce("shared", ID_B, "B", 3000))
        assertEquals(ID_B, dns.resolve("shared")?.deviceId)
    }

    @Test
    fun conflictTieBrokenBySmallerDeviceId() {
        val dns = registry(5000)
        assertTrue(dns.handleAnnounce("shared", ID_B, "B", 4000))
        // Same timestamp, smaller deviceId string wins ("aaaa..." < "bbbb...")
        assertTrue(dns.handleAnnounce("shared", ID_A, "A", 4000))
        assertEquals(ID_A, dns.resolve("shared")?.deviceId)
    }

    @Test
    fun invalidRemoteAnnounceRejected() {
        val dns = registry(5000)
        assertFalse(dns.handleAnnounce("bad name", ID_B, "B", 3000))
        assertEquals(0, dns.size)
    }

    // ---------------- Response payload ----------------

    @Test
    fun responsePayloadRoundTrip() {
        val dns = registry(1000)
        dns.registerSelf("asadbek", ID_A, "Asadbek")
        val payload = dns.buildResponsePayload("asadbek")
        assertNotNull(payload)
        val data = DnsRegistry.parseResponse(payload!!)
        assertNotNull(data)
        assertEquals("asadbek", data!!.hostname)
        assertEquals(ID_A, data.deviceId)
        assertTrue(data.found)
    }

    @Test
    fun parseResponseRejectsGarbage() {
        assertNull(DnsRegistry.parseResponse("no-pipes"))
        assertNull(DnsRegistry.parseResponse("h|d|maybe|123"))
        assertNull(DnsRegistry.parseResponse("h|d|1|notanumber"))
        assertNull(DnsRegistry.parseResponse("h|d|1"))
    }

    @Test
    fun handleResponseCachesEntry() {
        val dns = registry(9000)
        val data = DnsRegistry.ResponseData("remote", ID_B, true, 8000)
        assertTrue(dns.handleResponse(data))
        assertEquals(ID_B, dns.resolve("remote")?.deviceId)
    }

    @Test
    fun handleResponseNotFoundIgnored() {
        val dns = registry(9000)
        assertFalse(dns.handleResponse(DnsRegistry.ResponseData("x", ID_B, false, 8000)))
        assertEquals(0, dns.size)
    }

    // ---------------- TTL & expiry ----------------

    @Test
    fun expiredEntriesNotResolved() {
        val dns = registry(1000, DnsRegistry.DEFAULT_TTL_MS + 2000)
        dns.registerSelf("old", ID_A, "A")
        assertNull(dns.resolve("old"))
        assertEquals(0, dns.size)
    }

    @Test
    fun expireRemovesOnlyStale() {
        val dns = registry(
            1000, // register old
            2000, // register fresh
            DnsRegistry.DEFAULT_TTL_MS + 1500, // expire sweep
        )
        dns.registerSelf("old", ID_A, "A")
        dns.registerSelf("fresh", ID_B, "B")
        assertEquals(1, dns.expire())
        assertNull(dns.resolve("old"))
        assertNotNull(dns.resolve("fresh"))
    }

    // ---------------- Reverse lookup & snapshot ----------------

    @Test
    fun reverseLookupFindsByDeviceId() {
        val dns = registry(1000)
        dns.registerSelf("asadbek", ID_A, "Asadbek")
        assertEquals("asadbek", dns.reverseLookup(ID_A)?.hostname)
        assertNull(dns.reverseLookup(ID_B))
    }

    @Test
    fun snapshotSortedByHostname() {
        val dns = registry(1000)
        dns.registerSelf("zeta", ID_A, "A")
        dns.registerSelf("alpha", ID_B, "B")
        assertEquals(listOf("alpha", "zeta"), dns.snapshot().map { it.hostname })
    }

    @Test
    fun maxHostsEvictsOldestRefresh() {
        var t = 1000L
        val dns = DnsRegistry { t++ }
        dns.registerSelf("first", ID_A, "A")
        // Fill to capacity with remote hosts (each gets a later refresh time)
        for (i in 0 until DnsRegistry.MAX_HOSTS - 1) {
            assertTrue(dns.handleAnnounce("host$i", ID_B, "B", 1000L + i))
        }
        assertEquals(DnsRegistry.MAX_HOSTS, dns.size)
        // One more -> oldest ("first", refreshed first) evicted
        assertTrue(dns.handleAnnounce("extra", ID_B, "B", 99999))
        assertNull(dns.resolve("first"))
        assertNotNull(dns.resolve("extra"))
    }
}
