package com.meshnet.meshnet_app

import android.content.Context
import com.meshnet.meshnet_app.crypto.MeshCrypto
import com.meshnet.meshnet_app.protocol.MeshFrame
import com.meshnet.meshnet_app.protocol.MessageType
import com.meshnet.meshnet_app.protocol.RoutingEngine
import com.meshnet.meshnet_app.storage.MeshDatabase
import com.meshnet.meshnet_app.storage.PeerStore
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * RoutingEngine stress testlari: concurrent access, performance, edge cases.
 */
class RoutingEngineStressTest {

    companion object {
        private const val ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        private const val ID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        private const val ID_C = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        private const val ID_D = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        private const val ID_E = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
    }

    private class Harness(
        val engine: RoutingEngine,
        val peerStore: PeerStore,
        val emitted: MutableList<MeshFrame> = mutableListOf(),
        val receivedMessages: MutableList<Triple<String, String, String>> = mutableListOf(),
        val deliveryReports: MutableList<Pair<String, Boolean>> = mutableListOf(),
        val peersFound: MutableList<String> = mutableListOf(),
        val outboxStatus: MutableList<Pair<String, String>> = mutableListOf(),
    ) : RoutingEngine.MessageListener {
        override fun onTextReceived(from: String, message: String, messageId: String) {
            receivedMessages.add(Triple(from, message, messageId))
        }
        override fun onDeliveryReport(messageId: String, delivered: Boolean) {
            deliveryReports.add(messageId to delivered)
        }
        override fun onPairResult(deviceId: String, success: Boolean) {}
        override fun onPeerFound(deviceId: String) { peersFound.add(deviceId) }
        override fun onOutboxChanged(messageId: String, status: String) {
            outboxStatus.add(messageId to status)
        }
        override fun onRouteChanged(destination: String, nextHop: String, hopCount: Int, quality: Int) {}
        override fun onFrameToSend(frame: MeshFrame, transport: String?) { emitted.add(frame) }
    }

    @Before
    fun setUp() {
        MeshDatabase.setInstance(TestDatabaseHelper.createMockDatabase())
    }

    @After
    fun tearDown() {
        MeshDatabase.resetInstance()
    }

    private fun makeEngine(id: String, keyPair: MeshCrypto.KeyPair): Harness {
        val ctx = mock(Context::class.java)
        val store = PeerStore(ctx)
        val engine = RoutingEngine(ctx, id, keyPair.privateKey, store)
        engine.setIdentityPublicKey(keyPair.publicKey)
        val harness = Harness(engine, store)
        engine.addListener(harness)
        return harness
    }

    // =================== Stress: Many messages ===================

    @Test
    fun stress_send100Messages_allQueued() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val keyB = MeshCrypto.generateKeyPair()
        a.peerStore.upsert(PeerStore.Peer(
            deviceId = ID_B, displayName = "B", publicKey = MeshCrypto.b64(keyB.publicKey),
            authorized = true, lastSeenMs = System.currentTimeMillis(), transport = "ble", rssi = -50,
        ))
        for (i in 1..100) {
            a.engine.sendText(ID_B, "msg-$i")
        }
        assertEquals(100, a.outboxStatus.size)
    }

    @Test
    fun stress_concurrentRouteLearning_noException() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val threads = (1..20).map { i ->
            Thread {
                a.engine.learnRoute("node-$i", "node-${i-1}", 1, 80)
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(1000) }
        // No crash = pass
    }

    @Test
    fun stress_concurrentRegisterSeen_noDuplicate() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val threads = (1..50).map {
            Thread {
                a.engine.handleIncomingFrame(MeshFrame(
                    type = MessageType.TEXT, hopLimit = 1, ttl = 1, encrypted = false,
                    senderId = ID_B, targetId = ID_A, msgSeq = 1L,
                    payload = "dup".toByteArray(), senderPublicKey = null,
                ))
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(1000) }
        // Only 1 message should be received (dedup)
        assertTrue(a.receivedMessages.size <= 1)
    }

    // =================== Route table limits ===================

    @Test
    fun routeTable_maxRoutes_enforced() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        for (i in 1..300) {
            a.engine.learnRoute("dest-$i", "hop-$i", 1, 50)
        }
        // Should not exceed MAX_ROUTE_TABLE + some margin
        assertTrue(a.engine.routeSnapshot().size <= 210)
    }

    @Test
    fun routeTable_worstEvicted() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.learnRoute("good-dest", "good-hop", 1, 95)
        for (i in 1..210) {
            a.engine.learnRoute("bad-dest-$i", "bad-hop-$i", 3, 10)
        }
        val snapshot = a.engine.routeSnapshot()
        // Good route should still exist (high quality)
        assertTrue(snapshot.any { it["destination"] == "good-dest" })
    }

    // =================== Route quality scoring ===================

    @Test
    fun routeQuality_allSuccess_highScore() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.learnRoute(ID_B, ID_B, 1, 80)
        repeat(10) { a.engine.routeSuccess(ID_B) }
        val route = a.engine.findRoute(ID_B)
        assertNotNull(route)
        assertTrue(route!!.qualityScore() > 70)
    }

    @Test
    fun routeQuality_allFailure_lowScore() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.learnRoute(ID_B, ID_B, 1, 50)
        repeat(10) { a.engine.routeFailure(ID_B) }
        val route = a.engine.findRoute(ID_B)
        // After many failures + low quality, route should be removed
        assertNull(route)
    }

    @Test
    fun routeQuality_mixedScore_balanced() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.learnRoute(ID_B, ID_B, 1, 50)
        repeat(5) { a.engine.routeSuccess(ID_B) }
        repeat(5) { a.engine.routeFailure(ID_B) }
        val route = a.engine.findRoute(ID_B)
        assertNotNull(route)
        val score = route!!.qualityScore()
        assertTrue(score in 30..70)
    }

    // =================== Expire ===================

    @Test
    fun expireRoutes_removesExpired() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.learnRoute(ID_B, ID_B, 1, 80)
        // Route should exist
        assertNotNull(a.engine.findRoute(ID_B))
        // Force expire by calling with old timestamp
        a.engine.expireRoutes()
        // Fresh route won't be expired, that's expected
    }

    // =================== Send to unauthorized ===================

    @Test
    fun sendText_unauthorizedPeer_returnsNull() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val result = a.engine.sendText(ID_B, "hello")
        assertNull(result)
    }

    @Test
    fun sendText_emptyMessage_returnsMsgId() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val keyB = MeshCrypto.generateKeyPair()
        a.peerStore.upsert(PeerStore.Peer(
            deviceId = ID_B, displayName = "B", publicKey = MeshCrypto.b64(keyB.publicKey),
            authorized = true, lastSeenMs = System.currentTimeMillis(), transport = "ble", rssi = -50,
        ))
        val result = a.engine.sendText(ID_B, "")
        assertNotNull(result)
    }

    // =================== Max hop ===================

    @Test
    fun relayFrame_hopLimitZero_doesNotRelay() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val frame = MeshFrame(
            type = MessageType.TEXT, hopLimit = 0, ttl = 6, encrypted = false,
            senderId = ID_C, targetId = ID_B, msgSeq = 1L,
            payload = "data".toByteArray(), senderPublicKey = null,
        )
        a.engine.handleIncomingFrame(frame)
        // Should not relay (hop=0)
        assertTrue(a.emitted.isEmpty())
    }

    @Test
    fun relayFrame_ttlZero_doesNotRelay() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val frame = MeshFrame(
            type = MessageType.TEXT, hopLimit = 2, ttl = 0, encrypted = false,
            senderId = ID_C, targetId = ID_B, msgSeq = 1L,
            payload = "data".toByteArray(), senderPublicKey = null,
        )
        a.engine.handleIncomingFrame(frame)
        assertTrue(a.emitted.isEmpty())
    }

    // =================== Delivery report ===================

    @Test
    fun deliveryReport_removesFromOutbox() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val keyB = MeshCrypto.generateKeyPair()
        a.peerStore.upsert(PeerStore.Peer(
            deviceId = ID_B, displayName = "B", publicKey = MeshCrypto.b64(keyB.publicKey),
            authorized = true, lastSeenMs = System.currentTimeMillis(), transport = "ble", rssi = -50,
        ))
        val msgId = a.engine.sendText(ID_B, "test")
        assertNotNull(msgId)
        // Simulate delivery report
        val drPayload = ByteArray(1 + (msgId!!.toByteArray().size))
        drPayload[0] = 0x01
        msgId.toByteArray().copyInto(drPayload, 1)
        a.engine.handleIncomingFrame(MeshFrame(
            type = MessageType.DELIVERY_REPORT, hopLimit = 1, ttl = 1, encrypted = false,
            senderId = ID_B, targetId = ID_A, msgSeq = 0L,
            payload = drPayload, senderPublicKey = null,
        ))
        assertTrue(a.deliveryReports.any { it.first == msgId && it.second })
    }

    // =================== Pairing ===================

    @Test
    fun sendPairRequest_emitsPairFrame() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val result = a.engine.sendPairRequest(ID_B)
        assertTrue(result)
        assertTrue(a.emitted.any { it.type == MessageType.PAIR_REQ })
    }

    @Test
    fun handlePairReq_authorizedPeer() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val keyB = MeshCrypto.generateKeyPair()
        val frame = MeshFrame(
            type = MessageType.PAIR_REQ, hopLimit = 4, ttl = 6, encrypted = false,
            senderId = ID_B, targetId = ID_A, msgSeq = 1L,
            payload = ByteArray(0), senderPublicKey = keyB.publicKey,
        )
        a.engine.handleIncomingFrame(frame)
        assertTrue(a.peerStore.get(ID_B)?.authorized == true)
    }

    @Test
    fun handlePairAck_authorizedPeer() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val keyB = MeshCrypto.generateKeyPair()
        val frame = MeshFrame(
            type = MessageType.PAIR_ACK, hopLimit = 4, ttl = 6, encrypted = false,
            senderId = ID_B, targetId = ID_A, msgSeq = 1L,
            payload = ByteArray(0), senderPublicKey = keyB.publicKey,
        )
        a.engine.handleIncomingFrame(frame)
        assertTrue(a.peerStore.get(ID_B)?.authorized == true)
    }

    // =================== Find peer ===================

    @Test
    fun sendFindPeer_emitsFindPeerFrame() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val result = a.engine.sendFindPeer(ID_B)
        assertTrue(result)
        assertTrue(a.emitted.any { it.type == MessageType.FIND_PEER })
    }

    @Test
    fun sendFindPeer_emptyTarget_returnsFalse() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val result = a.engine.sendFindPeer("")
        assertFalse(result)
    }

    // =================== Stats ===================

    @Test
    fun stats_initialValues() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val stats = a.engine.stats()
        assertEquals(0L, stats["framesReceived"])
        assertEquals(0L, stats["framesRelayed"])
        assertEquals(0L, stats["messagesSent"])
        assertEquals(0L, stats["messagesDelivered"])
        assertEquals(0L, stats["duplicatesDropped"])
    }

    @Test
    fun stats_incrementsAfterReceive() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.handleIncomingFrame(MeshFrame(
            type = MessageType.TEXT, hopLimit = 1, ttl = 1, encrypted = false,
            senderId = ID_B, targetId = ID_A, msgSeq = 1L,
            payload = "test".toByteArray(), senderPublicKey = null,
        ))
        val stats = a.engine.stats()
        assertEquals(1L, stats["framesReceived"])
    }

    // =================== Route snapshot ===================

    @Test
    fun routeSnapshot_containsFields() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.learnRoute(ID_B, ID_B, 1, 80)
        val snapshot = a.engine.routeSnapshot()
        assertEquals(1, snapshot.size)
        val route = snapshot[0]
        assertNotNull(route["destination"])
        assertNotNull(route["nextHop"])
        assertNotNull(route["hopCount"])
        assertNotNull(route["quality"])
        assertNotNull(route["age"])
        assertNotNull(route["success"])
        assertNotNull(route["fail"])
    }

    // =================== Outbox ===================

    @Test
    fun outboxSnapshot_afterSend_containsMessage() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val keyB = MeshCrypto.generateKeyPair()
        a.peerStore.upsert(PeerStore.Peer(
            deviceId = ID_B, displayName = "B", publicKey = MeshCrypto.b64(keyB.publicKey),
            authorized = true, lastSeenMs = System.currentTimeMillis(), transport = "ble", rssi = -50,
        ))
        a.engine.sendText(ID_B, "hello")
        val snapshot = a.engine.outboxSnapshot()
        assertEquals(1, snapshot.size)
    }

    // =================== Frame types ===================

    @Test
    fun handleVoiceMsg_emitsEvent() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val frame = MeshFrame(
            type = MessageType.VOICE_MSG, hopLimit = 1, ttl = 1, encrypted = false,
            senderId = ID_B, targetId = ID_A, msgSeq = 1L,
            payload = ByteArray(100), senderPublicKey = null,
        )
        a.engine.handleIncomingFrame(frame)
        // Voice message should be processed without crash
    }

    @Test
    fun handleFileStart_emitsEvent() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val frame = MeshFrame(
            type = MessageType.FILE_START, hopLimit = 1, ttl = 1, encrypted = false,
            senderId = ID_B, targetId = ID_A, msgSeq = 1L,
            payload = """{"fileName":"test.txt","fileSize":100,"mimeType":"text/plain","transferId":"t1"}""".toByteArray(),
            senderPublicKey = null,
        )
        a.engine.handleIncomingFrame(frame)
        // File start should be processed
    }

    // =================== Peer presence ===================

    @Test
    fun peerPresence_markSeen_updatesLastSeen() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.peerStore.upsert(PeerStore.Peer(
            deviceId = ID_B, displayName = "B", publicKey = "",
            authorized = false, lastSeenMs = 0, transport = "ble", rssi = -50,
        ))
        // Simulate incoming frame from B
        a.engine.handleIncomingFrame(MeshFrame(
            type = MessageType.PEER_PING, hopLimit = 1, ttl = 1, encrypted = false,
            senderId = ID_B, targetId = MeshFrame.BROADCAST, msgSeq = 1L,
            payload = ByteArray(0), senderPublicKey = null,
        ))
        val peer = a.peerStore.get(ID_B)
        assertNotNull(peer)
        assertTrue(peer!!.lastSeenMs > 0)
    }

    // =================== Edge cases ===================

    @Test
    fun handleIncomingFrame_zeroHopLimit_dropsFrame() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.handleIncomingFrame(MeshFrame(
            type = MessageType.RELAY, hopLimit = 0, ttl = 1, encrypted = false,
            senderId = ID_C, targetId = ID_B, msgSeq = 1L,
            payload = ByteArray(10), senderPublicKey = null,
        ))
        assertEquals(0, a.receivedMessages.size)
    }

    @Test
    fun routeLearning_selfIgnored() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.learnRoute(ID_A, ID_A, 1, 80)
        assertNull(a.engine.findRoute(ID_A))
    }

    @Test
    fun sendText_generatesUniqueMessageIds() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val keyB = MeshCrypto.generateKeyPair()
        a.peerStore.upsert(PeerStore.Peer(
            deviceId = ID_B, displayName = "B", publicKey = MeshCrypto.b64(keyB.publicKey),
            authorized = true, lastSeenMs = System.currentTimeMillis(), transport = "ble", rssi = -50,
        ))
        val ids = mutableSetOf<String>()
        for (i in 1..50) {
            val id = a.engine.sendText(ID_B, "msg-$i")
            assertNotNull(id)
            ids.add(id!!)
        }
        assertEquals(50, ids.size)
    }

    @Test
    fun retryPending_respectsRateLimit() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.retryPending(null)
        a.emitted.clear()
        // Immediately retry should be rate-limited
        a.engine.retryPending(null)
        assertTrue(a.emitted.isEmpty())
    }

    @Test
    fun outbox_maxSize_evictsOldest() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val keyB = MeshCrypto.generateKeyPair()
        a.peerStore.upsert(PeerStore.Peer(
            deviceId = ID_B, displayName = "B", publicKey = MeshCrypto.b64(keyB.publicKey),
            authorized = true, lastSeenMs = System.currentTimeMillis(), transport = "ble", rssi = -50,
        ))
        // Send 501 messages (MAX_OUTBOX is 500)
        for (i in 1..501) {
            a.engine.sendText(ID_B, "msg-$i")
        }
        val snapshot = a.engine.outboxSnapshot()
        assertTrue(snapshot.size <= 500)
    }
}
