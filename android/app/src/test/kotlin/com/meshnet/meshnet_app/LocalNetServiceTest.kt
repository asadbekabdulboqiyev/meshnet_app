package com.meshnet.meshnet_app

import android.content.Context
import com.meshnet.meshnet_app.localnet.LocalNetService
import com.meshnet.meshnet_app.protocol.MeshFrame
import com.meshnet.meshnet_app.protocol.MessageType
import com.meshnet.meshnet_app.protocol.RoutingEngine
import com.meshnet.meshnet_app.storage.MeshDatabase
import com.meshnet.meshnet_app.storage.PeerStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

/**
 * LocalNetService testlari: DNS frame qayta ishlash (announce/query/response),
 * hostname sanitizatsiya, RoutingEngine integratsiyasi (emitted frames).
 */
class LocalNetServiceTest {

    companion object {
        private const val ID_SELF = "11111111-1111-1111-1111-111111111111"
        private const val ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    }

    private lateinit var routing: RoutingEngine
    private lateinit var peerStore: PeerStore
    private lateinit var service: LocalNetService
    private var now: Long = 10_000

    @get:Rule
    val tmp = TemporaryFolder()

    private class Harness(
        val service: LocalNetService,
        val discovered: MutableList<Pair<String, String>> = mutableListOf(),
        val resolved: MutableList<Pair<String, String?>> = mutableListOf(),
        val syncProgress: MutableList<SyncEvent> = mutableListOf(),
    ) : LocalNetService.Listener {
        data class SyncEvent(val fileId: String, val fileName: String, val have: Int, val total: Int, val state: String, val filePath: String)

        override fun onHostDiscovered(hostname: String, deviceId: String) {
            discovered.add(hostname to deviceId)
        }
        override fun onHostResolved(hostname: String, deviceId: String?) {
            resolved.add(hostname to deviceId)
        }
        override fun onFileSyncProgress(fileId: String, fileName: String, have: Int, total: Int, state: String, filePath: String) {
            syncProgress.add(SyncEvent(fileId, fileName, have, total, state, filePath))
        }
    }

    private var harness: Harness? = null

    @Before
    fun setUp() {
        MeshDatabase.setInstance(TestDatabaseHelper.createMockDatabase())
        routing = RoutingEngine(mock(Context::class.java), ID_SELF, ByteArray(32), PeerStore(mock(Context::class.java)))
        service = LocalNetService(
            selfDeviceId = ID_SELF,
            selfDisplayName = "Asadbek Dev",
            routing = routing,
            baseDir = tmp.newFolder(),
            nowMs = { now },
        )
        // Deterministic endpoint for tests (no LAN discovery)
        service.selfEndpointProvider = { null to -1 }
        harness = Harness(service).also { service.addListener(it) }
    }

    @After
    fun tearDown() {
        service.stop()
        MeshDatabase.resetInstance()
    }

    private fun announceFrame(sender: String, payload: String, seq: Long = 1) = MeshFrame(
        type = MessageType.DNS_ANNOUNCE,
        hopLimit = 4, ttl = 6, encrypted = false,
        senderId = sender, targetId = MeshFrame.BROADCAST,
        msgSeq = seq, payload = payload.toByteArray(), senderPublicKey = null,
    )

    private fun queryFrame(sender: String, hostname: String, seq: Long = 2) = MeshFrame(
        type = MessageType.DNS_QUERY,
        hopLimit = 4, ttl = 6, encrypted = false,
        senderId = sender, targetId = MeshFrame.BROADCAST,
        msgSeq = seq, payload = hostname.toByteArray(), senderPublicKey = null,
    )

    private fun responseFrame(sender: String, target: String, payload: String, seq: Long = 3) = MeshFrame(
        type = MessageType.DNS_RESPONSE,
        hopLimit = 4, ttl = 6, encrypted = false,
        senderId = sender, targetId = target,
        msgSeq = seq, payload = payload.toByteArray(), senderPublicKey = null,
    )

    // ---------------- Hostname sanitization ----------------

    @Test
    fun displayNameToHostname() {
        assertEquals("asadbek-dev", LocalNetService.hostnameFromDisplayName("Asadbek Dev"))
        assertEquals("node", LocalNetService.hostnameFromDisplayName("!!!"))
        assertEquals("a-b", LocalNetService.hostnameFromDisplayName("A B"))
        assertEquals("odam_ismi".replace("_", "-"), LocalNetService.hostnameFromDisplayName("Odam_Ismi"))
    }

    // ---------------- Start / stop ----------------

    @Test
    fun startRegistersSelfHostnameAndStartsHttp() {
        val port = service.start()
        assertTrue(port > 0)
        assertEquals("asadbek-dev", service.selfHostname)
        assertNotNull(service.httpServer)
        assertTrue(service.httpServer!!.isRunning)
        // Own host resolvable locally
        assertEquals(ID_SELF, service.dns.resolve("asadbek-dev")?.deviceId)
    }

    @Test
    fun stopShutsHttpDown() {
        service.start()
        service.stop()
        assertNull(service.httpServer)
    }

    // ---------------- Announce handling ----------------

    @Test
    fun remoteAnnounceLearnedAndEmitted() {
        service.onDnsAnnounce(announceFrame(ID_A, "alpha|5000"))
        assertEquals(listOf("alpha" to ID_A), harness!!.discovered)
        assertEquals(ID_A, service.dns.resolve("alpha")?.deviceId)
    }

    @Test
    fun ownAnnounceIgnored() {
        service.onDnsAnnounce(announceFrame(ID_SELF, "selfname|5000"))
        assertTrue(harness!!.discovered.isEmpty())
    }

    @Test
    fun malformedAnnounceIgnored() {
        service.onDnsAnnounce(announceFrame(ID_A, "garbage-no-pipe"))
        assertTrue(harness!!.discovered.isEmpty())
    }

    // ---------------- Query handling ----------------

    @Test
    fun queryForKnownHostAnswered() {
        service.start()
        val emitted = mutableListOf<MeshFrame>()
        val listener = object : RoutingEngine.MessageListener {
            override fun onTextReceived(from: String, message: String, messageId: String) {}
            override fun onDeliveryReport(messageId: String, delivered: Boolean) {}
            override fun onPairResult(deviceId: String, success: Boolean) {}
            override fun onPeerFound(deviceId: String) {}
            override fun onOutboxChanged(messageId: String, status: String) {}
            override fun onFrameToSend(frame: MeshFrame, transport: String?) { emitted.add(frame) }
            override fun onRouteChanged(destination: String, nextHop: String, hopCount: Int, quality: Int) {}
        }
        routing.addListener(listener)
        service.onDnsQuery(queryFrame(ID_A, "asadbek-dev"))
        val resp = emitted.filter { it.type == MessageType.DNS_RESPONSE }
        assertEquals(1, resp.size)
        assertEquals(ID_A, resp[0].targetId)
        assertTrue(String(resp[0].payload).startsWith("asadbek-dev|$ID_SELF|1|"))
    }

    @Test
    fun queryForUnknownHostNotAnswered() {
        val emitted = mutableListOf<MeshFrame>()
        val listener = object : RoutingEngine.MessageListener {
            override fun onTextReceived(from: String, message: String, messageId: String) {}
            override fun onDeliveryReport(messageId: String, delivered: Boolean) {}
            override fun onPairResult(deviceId: String, success: Boolean) {}
            override fun onPeerFound(deviceId: String) {}
            override fun onOutboxChanged(messageId: String, status: String) {}
            override fun onFrameToSend(frame: MeshFrame, transport: String?) { emitted.add(frame) }
            override fun onRouteChanged(destination: String, nextHop: String, hopCount: Int, quality: Int) {}
        }
        routing.addListener(listener)
        service.onDnsQuery(queryFrame(ID_A, "ghost"))
        assertTrue(emitted.none { it.type == MessageType.DNS_RESPONSE })
    }

    // ---------------- Response handling ----------------

    @Test
    fun responseForUsCachedAndEmitted() {
        service.resolve("remote-host") // floods query, caches pending
        service.onDnsResponse(responseFrame(ID_A, ID_SELF, "remote-host|$ID_A|1|9000"))
        assertEquals(listOf<Pair<String, String?>>("remote-host" to ID_A), harness!!.resolved)
        assertEquals(ID_A, service.dns.resolve("remote-host")?.deviceId)
    }

    @Test
    fun responseForOthersIgnored() {
        service.onDnsResponse(responseFrame(ID_A, "other-device", "x|$ID_A|1|9000"))
        assertTrue(harness!!.resolved.isEmpty())
    }

    // ---------------- Resolve ----------------

    @Test
    fun resolveLocalHitNoQueryFlooded() {
        service.start()
        val entry = service.resolve("asadbek-dev")
        assertNotNull(entry)
        assertEquals(ID_SELF, entry?.deviceId)
    }

    @Test
    fun resolveLocalMissFloodsQueryOnce() {
        val emitted = mutableListOf<MeshFrame>()
        val listener = object : RoutingEngine.MessageListener {
            override fun onTextReceived(from: String, message: String, messageId: String) {}
            override fun onDeliveryReport(messageId: String, delivered: Boolean) {}
            override fun onPairResult(deviceId: String, success: Boolean) {}
            override fun onPeerFound(deviceId: String) {}
            override fun onOutboxChanged(messageId: String, status: String) {}
            override fun onFrameToSend(frame: MeshFrame, transport: String?) { emitted.add(frame) }
            override fun onRouteChanged(destination: String, nextHop: String, hopCount: Int, quality: Int) {}
        }
        routing.addListener(listener)
        assertNull(service.resolve("ghost"))
        assertNull(service.resolve("ghost")) // dedup window -> no second flood
        assertEquals(1, emitted.count { it.type == MessageType.DNS_QUERY })
    }

    // ---------------- Periodic work ----------------

    @Test
    fun periodicWorkAnnouncesOwnHostname() {
        service.start()
        val emitted = mutableListOf<MeshFrame>()
        val listener = object : RoutingEngine.MessageListener {
            override fun onTextReceived(from: String, message: String, messageId: String) {}
            override fun onDeliveryReport(messageId: String, delivered: Boolean) {}
            override fun onPairResult(deviceId: String, success: Boolean) {}
            override fun onPeerFound(deviceId: String) {}
            override fun onOutboxChanged(messageId: String, status: String) {}
            override fun onFrameToSend(frame: MeshFrame, transport: String?) { emitted.add(frame) }
            override fun onRouteChanged(destination: String, nextHop: String, hopCount: Int, quality: Int) {}
        }
        routing.addListener(listener)
        service.periodicWork()
        val announces = emitted.filter { it.type == MessageType.DNS_ANNOUNCE }
        assertEquals(1, announces.size)
        assertEquals("asadbek-dev|${service.dns.snapshot().first().firstRegisteredMs}", String(announces[0].payload))
    }

    // ---------------- Snapshot ----------------

    @Test
    fun hostsSnapshotContainsSelfFlag() {
        service.start()
        service.onDnsAnnounce(announceFrame(ID_A, "alpha|5000"))
        val hosts = service.hostsSnapshot()
        assertEquals(2, hosts.size)
        val self = hosts.first { it["isSelf"] == true }
        assertEquals("asadbek-dev", self["hostname"])
        assertEquals("asadbek-dev.mesh", self["fqdn"])
        val alpha = hosts.first { it["hostname"] == "alpha" }
        assertEquals(false, alpha["isSelf"])
    }

    @Test
    fun nameConflictFallbackSuffix() {
        // Remote node claimed our future name strictly before us
        service.onDnsAnnounce(announceFrame(ID_A, "asadbek-dev|5000"))
        now = 20_000
        val port = service.start()
        assertTrue(port > 0)
        // Fallback name with device suffix used instead
        assertFalse(service.selfHostname == "asadbek-dev")
        assertTrue(service.selfHostname.startsWith("asadbek-dev-"))
    }

    // ---------------- Phase 2: announce with endpoint ----------------

    @Test
    fun announceWithEndpointLearned() {
        service.onDnsAnnounce(announceFrame(ID_A, "alpha|5000|8080|192.168.49.2"))
        val entry = service.dns.resolve("alpha")
        assertNotNull(entry)
        assertEquals(8080, entry?.httpPort)
        assertEquals("192.168.49.2", entry?.ipAddress)
        assertTrue(entry!!.hasEndpoint)
        assertTrue(service.hostsSnapshot().first { it["hostname"] == "alpha" }["hasEndpoint"] == true)
    }

    @Test
    fun announceWithoutEndpointKeepsLegacyCompat() {
        service.onDnsAnnounce(announceFrame(ID_A, "alpha|5000"))
        val entry = service.dns.resolve("alpha")
        assertNotNull(entry)
        assertEquals(-1, entry?.httpPort)
        assertFalse(entry!!.hasEndpoint)
    }

    @Test
    fun periodicAnnounceIncludesEndpointWhenAvailable() {
        service.start()
        service.selfEndpointProvider = { "10.0.0.9" to 8080 }
        val emitted = mutableListOf<MeshFrame>()
        routing.addListener(object : RoutingEngine.MessageListener {
            override fun onTextReceived(from: String, message: String, messageId: String) {}
            override fun onDeliveryReport(messageId: String, delivered: Boolean) {}
            override fun onPairResult(deviceId: String, success: Boolean) {}
            override fun onPeerFound(deviceId: String) {}
            override fun onOutboxChanged(messageId: String, status: String) {}
            override fun onFrameToSend(frame: MeshFrame, transport: String?) { emitted.add(frame) }
            override fun onRouteChanged(destination: String, nextHop: String, hopCount: Int, quality: Int) {}
        })
        service.periodicWork()
        val payload = String(emitted.first { it.type == MessageType.DNS_ANNOUNCE }.payload)
        val parts = payload.split("|")
        assertEquals(4, parts.size)
        assertEquals("8080", parts[2])
        assertEquals("10.0.0.9", parts[3])
    }

    // ---------------- Phase 2: file sharing ----------------

    @Test
    fun shareFileChunksStoresAndPersists() {
        val src = tmp.newFile("report.pdf")
        src.writeBytes(ByteArray(150_000) { (it % 256).toByte() })
        val manifest = service.shareFile(src.absolutePath)
        assertNotNull(manifest)
        assertEquals("report.pdf", manifest!!.fileName)
        assertEquals(150_000L, manifest.fileSize)
        assertTrue(manifest.chunks.size >= 3)
        // All chunks present in store
        manifest.chunks.forEach { assertTrue(service.chunkStore.has(it.hash)) }
        // Registered + persisted
        assertEquals(manifest, service.manifestById(manifest.fileId))
        assertTrue(service.sharedFiles().isNotEmpty())
    }

    @Test
    fun shareMissingFileReturnsNull() {
        assertNull(service.shareFile("/nonexistent/path.bin"))
    }

    @Test
    fun unshareRemovesManifestButKeepsChunks() {
        val src = tmp.newFile("data.bin")
        src.writeBytes(ByteArray(1000))
        val manifest = service.shareFile(src.absolutePath)!!
        val hash = manifest.chunks[0].hash
        assertTrue(service.unshareFile(manifest.fileId))
        assertNull(service.manifestById(manifest.fileId))
        // Chunks stay — other files may reference them
        assertTrue(service.chunkStore.has(hash))
    }

    @Test
    fun manifestsReloadedOnNewInstance() {
        val src = tmp.newFile("persist.bin")
        src.writeBytes(ByteArray(2000))
        val base = tmp.newFolder("persist-base")
        val first = LocalNetService(ID_SELF, "Asadbek Dev", routing, baseDir = base, nowMs = { now })
        val manifest = first.shareFile(src.absolutePath)!!
        // New instance over the SAME base dir ("app restart")
        val revived = LocalNetService(ID_SELF, "Asadbek Dev", routing, baseDir = base, nowMs = { now })
        assertEquals(manifest.fileId, revived.manifestById(manifest.fileId)?.fileId)
        first.stop()
        revived.stop()
    }

    @Test
    fun fetchWithoutEndpointFailsFast() {
        service.onDnsAnnounce(announceFrame(ID_A, "alpha|5000")) // no ip/port
        assertTrue(!service.fetchFile("alpha", "somefileid"))
        assertEquals("failed", harness!!.syncProgress.last().state)
    }
}
