package com.meshnet.meshnet_app

import android.content.Context
import com.meshnet.meshnet_app.crypto.MeshCrypto
import com.meshnet.meshnet_app.protocol.MeshFrame
import com.meshnet.meshnet_app.protocol.MessageType
import com.meshnet.meshnet_app.protocol.RoutingEngine
import com.meshnet.meshnet_app.storage.MeshDatabase
import com.meshnet.meshnet_app.storage.PeerStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * RoutingEngine tests: 2-hop relay, duplicate control, E2E encryption, and
 * delivery reports. Uses a real PeerStore instance (with a mocked SharedPreferences),
 * so PeerStore has an in-memory cache.
 */
class RoutingEngineTest {

    companion object {
        private const val ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        private const val ID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        private const val ID_C = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        private const val ID_M = "dddddddd-dddd-dddd-dddd-dddddddddddd"
    }

    /** Listener + emitted-frames list for each engine. */
    private class Harness(
        val engine: RoutingEngine,
        val peerStore: PeerStore,
        val emitted: MutableList<MeshFrame> = mutableListOf(),
        val receivedMessages: MutableList<Triple<String, String, String>> = mutableListOf(),
        val deliveryReports: MutableList<Pair<String, Boolean>> = mutableListOf(),
        val pairResults: MutableList<Pair<String, Boolean>> = mutableListOf(),
        val peersFound: MutableList<String> = mutableListOf(),
        val outboxStatus: MutableList<Pair<String, String>> = mutableListOf(),
    ) : RoutingEngine.MessageListener {
        override fun onTextReceived(from: String, message: String, messageId: String) {
            receivedMessages.add(Triple(from, message, messageId))
        }

        override fun onDeliveryReport(messageId: String, delivered: Boolean) {
            deliveryReports.add(messageId to delivered)
        }

        override fun onPairResult(deviceId: String, success: Boolean) {
            pairResults.add(deviceId to success)
        }

        override fun onPeerFound(deviceId: String) {
            peersFound.add(deviceId)
        }

        override fun onOutboxChanged(messageId: String, status: String) {
            outboxStatus.add(messageId to status)
        }

        override fun onRouteChanged(destination: String, nextHop: String, hopCount: Int, quality: Int) {}

        override fun onFrameToSend(frame: MeshFrame, transport: String?) {
            emitted.add(frame)
        }
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

    @Test
    fun sendText_toAuthorizedPeer_emitsEncryptedFrame() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val keyB = MeshCrypto.generateKeyPair()
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))

        val msgId = a.engine.sendText(ID_B, "hello friend")

        assertNotNull(msgId)
        assertEquals(1, a.emitted.size)
        val frame = a.emitted.first()
        assertEquals(MessageType.TEXT, frame.type)
        assertEquals(ID_B, frame.targetId)
        assertTrue(frame.encrypted)
        assertEquals(4, frame.hopLimit)
    }

    @Test
    fun sendText_toUnauthorizedPeer_rejected() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())

        val msgId = a.engine.sendText(ID_B, "hello")

        assertNull(msgId)
        assertTrue(a.emitted.isEmpty())
    }

    @Test
    fun endToEnd_textDelivered_withDeliveryReport() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyB = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, keyB)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        b.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        val msgId = a.engine.sendText(ID_B, "hello friend")
        val textFrame = a.emitted.last()

        // B receives
        b.engine.handleIncomingFrame(textFrame)
        assertEquals(1, b.receivedMessages.size)
        assertEquals("hello friend", b.receivedMessages.first().second)
        assertEquals(msgId, b.receivedMessages.first().third)

        // B sends a delivery report
        val reportFrame = b.emitted.last()
        assertEquals(MessageType.DELIVERY_REPORT, reportFrame.type)
        assertEquals(ID_A, reportFrame.targetId)

        // A processes the report
        a.engine.handleIncomingFrame(reportFrame)
        assertEquals(1, a.deliveryReports.size)
        assertEquals(msgId, a.deliveryReports.first().first)
        assertTrue(a.deliveryReports.first().second)
    }

    @Test
    fun duplicateFrame_dropped() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyB = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, keyB)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        b.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        a.engine.sendText(ID_B, "duplicate")
        val textFrame = a.emitted.last()

        b.engine.handleIncomingFrame(textFrame)
        b.engine.handleIncomingFrame(textFrame) // duplicate

        assertEquals(1, b.receivedMessages.size)
        assertEquals(1, b.emitted.filter { it.type == MessageType.DELIVERY_REPORT }.size)
    }

    @Test
    fun middleNode_relaysTextToFinalTarget() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyC = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val m = makeEngine(ID_M, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_C, MeshCrypto.b64(keyC.publicKey))

        a.engine.sendText(ID_C, "message relayed through"
)
        val textFrame = a.emitted.last()

        // M receives: target is C (not M), hopLimit 2 > 0 -> relay
        m.engine.handleIncomingFrame(textFrame)

        assertEquals(1, m.emitted.size)
        val relay = m.emitted.first()
        assertEquals(MessageType.RELAY, relay.type)

        val inner = MeshFrame.decode(relay.payload)!!
        assertEquals(MessageType.TEXT, inner.type)
        assertEquals(ID_C, inner.targetId)
        // The RELAY wrapper's hopLimit was decreased (4 -> 3)
        assertEquals(3, relay.hopLimit)
        assertEquals(4, inner.hopLimit)
    }

    @Test
    fun relay_stopsWhenHopLimitExhausted() {
        val m = makeEngine(ID_M, MeshCrypto.generateKeyPair())

        // hopLimit=1: nextHop=0 -> relay stops
        val frame = MeshFrame(
            type = MessageType.TEXT,
            hopLimit = 1,
            ttl = 6,
            encrypted = false,
            senderId = ID_A,
            targetId = ID_C,
            msgSeq = 123,
            payload = ByteArray(0),
            senderPublicKey = null,
        )

        m.engine.handleIncomingFrame(frame)
        assertTrue(m.emitted.isEmpty())
    }

    @Test
    fun pairReq_marksAuthorizedAndSendsAck() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyB = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, keyB)

        val req = MeshFrame(
            type = MessageType.PAIR_REQ,
            hopLimit = 2,
            ttl = 6,
            encrypted = false,
            senderId = ID_A,
            targetId = ID_B,
            msgSeq = 1,
            payload = ByteArray(0),
            senderPublicKey = keyA.publicKey,
        )

        b.engine.handleIncomingFrame(req)

        assertNotNull(b.peerStore.authorized(ID_A))
        assertEquals(1, b.emitted.size)
        val ack = b.emitted.first()
        assertEquals(MessageType.PAIR_ACK, ack.type)
        assertEquals(ID_A, ack.targetId)

        // A receives the ACK -> B becomes authorized
        a.engine.handleIncomingFrame(ack)
        assertNotNull(a.peerStore.authorized(ID_B))
    }

    @Test
    fun tamperedText_failsToDecrypt_reportsFailed() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyB = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, keyB)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        b.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        a.engine.sendText(ID_B, "encrypted message")
        val textFrame = a.emitted.last().let {
            it.copy(payload = it.payload.also { p -> p[p.size - 1] = (p.last().toInt() xor 0x01).toByte() })
        }

        b.engine.handleIncomingFrame(textFrame)

        // Cannot be opened: not delivered to the user, a failed report is sent
        assertTrue(b.receivedMessages.isEmpty())
        val report = b.emitted.last()
        assertEquals(MessageType.DELIVERY_REPORT, report.type)
        assertEquals(0x00, report.payload[0].toInt())
    }

    // ---------------- Phase 2: 2-hop relay (A -> B -> C) ----------------

    @Test
    fun e2e_2hop_AToBToC_deliveredAndReported() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyC = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, MeshCrypto.generateKeyPair())
        val c = makeEngine(ID_C, keyC)
        a.peerStore.markAuthorized(ID_C, MeshCrypto.b64(keyC.publicKey))
        c.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        // A -> C (2-hop via B)
        val msgId = a.engine.sendText(ID_C, "two-hop message")
        val textFrame = a.emitted.last()

        // B relays (emits RELAY)
        b.engine.handleIncomingFrame(textFrame)
        val relay = b.emitted.single { it.type == MessageType.RELAY }
        assertEquals(3, relay.hopLimit)

        // C receives the RELAY and delivers it (exactly once)
        c.engine.handleIncomingFrame(relay)
        assertEquals(1, c.receivedMessages.size)
        assertEquals("two-hop message", c.receivedMessages.first().second)
        assertEquals(msgId, c.receivedMessages.first().third)

        // C sends a delivery report to A (via B)
        val report = c.emitted.last()
        assertEquals(MessageType.DELIVERY_REPORT, report.type)
        assertEquals(ID_A, report.targetId)

        // B relays the report
        b.engine.handleIncomingFrame(report)
        val reportRelay = b.emitted.filter { it.type == MessageType.RELAY }.last()

        // A receives the report
        a.engine.handleIncomingFrame(reportRelay)
        assertEquals(1, a.deliveryReports.size)
        assertEquals(msgId, a.deliveryReports.first().first)
        assertTrue(a.deliveryReports.first().second)

        // Statistics counters (TEXT + DELIVERY_REPORT relay)
        assertEquals(2L, b.engine.stats()["framesRelayed"])
        assertEquals(1L, c.engine.stats()["messagesDelivered"])
        assertEquals(1L, a.engine.stats()["messagesSent"])
    }

    @Test
    fun duplicateRelay_deliversOnce() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyC = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, MeshCrypto.generateKeyPair())
        val c = makeEngine(ID_C, keyC)
        a.peerStore.markAuthorized(ID_C, MeshCrypto.b64(keyC.publicKey))
        c.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        a.engine.sendText(ID_C, "duplicate relay")
        b.engine.handleIncomingFrame(a.emitted.last())
        val relay = b.emitted.single { it.type == MessageType.RELAY }

        // A duplicate copy of the RELAY also reaches C (flood)
        c.engine.handleIncomingFrame(relay)
        c.engine.handleIncomingFrame(relay)

        assertEquals(1, c.receivedMessages.size)
        // Only one delivery report (not for the duplicate)
        assertEquals(1, c.emitted.filter { it.type == MessageType.DELIVERY_REPORT }.size)
        assertEquals(1L, c.engine.stats()["duplicatesDropped"])
    }

    @Test
    fun floodLoopback_ownText_notRelayedOrDelivered() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_C, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))

        a.engine.sendText(ID_C, "loop-back")
        val textFrame = a.emitted.last()
        a.emitted.clear()

        // Our own frame returning over the flood (A <- B <- A)
        a.engine.handleIncomingFrame(textFrame)

        assertTrue(a.receivedMessages.isEmpty()) // own message is not delivered
        assertTrue(a.emitted.isEmpty()) // not relayed again
        assertEquals(1L, a.engine.stats()["duplicatesDropped"])
    }

    @Test
    fun floodLoopback_ownDeliveryReport_dropped() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyC = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val c = makeEngine(ID_C, keyC)
        a.peerStore.markAuthorized(ID_C, MeshCrypto.b64(keyC.publicKey))
        c.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        a.engine.sendText(ID_C, "report loop-back")
        c.engine.handleIncomingFrame(a.emitted.last())
        val report = c.emitted.last()

        // Report C->A direct and (duplicate path) flood copies
        a.engine.handleIncomingFrame(report)
        a.engine.handleIncomingFrame(report)

        assertEquals(1, a.deliveryReports.size)
        assertEquals(1L, a.engine.stats()["duplicatesDropped"])
    }

    // ---------------- Phase 3: QR pairing network flow ----------------

    @Test
    fun qrPairing_scanTriggersPairReq_BauthorizesA_andAck() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyB = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, keyB)

        // A scans B's QR: considers B authorized (QR trust)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))

        // A sends PAIR_REQ (so that peer B also knows us)
        val sent = a.engine.sendPairRequest(ID_B)
        assertTrue(sent)
        val req = a.emitted.last()
        assertEquals(MessageType.PAIR_REQ, req.type)
        assertEquals(ID_B, req.targetId)
        assertNotNull(req.senderPublicKey)

        // B receives: A becomes authorized, sends PAIR_ACK
        b.engine.handleIncomingFrame(req)
        assertNotNull(b.peerStore.authorized(ID_A))
        val ack = b.emitted.last()
        assertEquals(MessageType.PAIR_ACK, ack.type)
        assertEquals(ID_A, ack.targetId)

        // A receives the ACK: pairResult(B, true)
        a.engine.handleIncomingFrame(ack)
        assertEquals(listOf(ID_B to true), a.pairResults)
        // B also accepted A as authorized — two-way pairing
        assertNotNull(b.peerStore.authorized(ID_A))
    }

    @Test
    fun qrPairing_2hop_AtoC_viaB_relayed() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyC = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, MeshCrypto.generateKeyPair())
        val c = makeEngine(ID_C, keyC)

        a.peerStore.markAuthorized(ID_C, MeshCrypto.b64(keyC.publicKey))

        // A -> C: PAIR_REQ (2-hop via B)
        a.engine.sendPairRequest(ID_C)
        val req = a.emitted.last()
        b.engine.handleIncomingFrame(req)
        val relayed = b.emitted.single { it.type == MessageType.RELAY }

        // C receives -> A authorized + ACK
        c.engine.handleIncomingFrame(relayed)
        assertNotNull(c.peerStore.authorized(ID_A))
        val ack = c.emitted.last()
        assertEquals(MessageType.PAIR_ACK, ack.type)

        // ACK to A via B
        b.engine.handleIncomingFrame(ack)
        val ackRelay = b.emitted.filter { it.type == MessageType.RELAY }.last()
        a.engine.handleIncomingFrame(ackRelay)
        assertEquals(listOf(ID_C to true), a.pairResults)
    }

    // ---------------- Phase 4: FIND_PEER (route recovery) ----------------

    @Test
    fun findPeer_broadcast_targetRepliesAck() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val c = makeEngine(ID_C, MeshCrypto.generateKeyPair())

        // A looks for C (findable even if not authorized)
        val sent = a.engine.sendFindPeer(ID_C)
        assertTrue(sent)
        val find = a.emitted.last()
        assertEquals(MessageType.FIND_PEER, find.type)
        assertEquals(MeshFrame.BROADCAST, find.targetId)
        assertEquals(ID_C, String(find.payload, Charsets.UTF_8))

        // C receives: it is the one being searched — sends ACK
        c.engine.handleIncomingFrame(find)
        val ack = c.emitted.last()
        assertEquals(MessageType.FIND_PEER_ACK, ack.type)
        assertEquals(ID_A, ack.targetId)

        // A receives the ACK: peerFound(C)
        a.engine.handleIncomingFrame(ack)
        assertEquals(listOf(ID_C), a.peersFound)
        // The ACK carried the public key — C became authorized automatically
        assertNotNull(a.peerStore.authorized(ID_C))
    }

    @Test
    fun findPeer_2hop_viaB_ackRelayed() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val b = makeEngine(ID_B, MeshCrypto.generateKeyPair())
        val c = makeEngine(ID_C, MeshCrypto.generateKeyPair())

        a.engine.sendFindPeer(ID_C)
        val find = a.emitted.last()

        // B relays the broadcast search
        b.engine.handleIncomingFrame(find)
        val relayed = b.emitted.single { it.type == MessageType.RELAY }

        // C receives -> ACK to A
        c.engine.handleIncomingFrame(relayed)
        val ack = c.emitted.last()
        assertEquals(MessageType.FIND_PEER_ACK, ack.type)

        // ACK reaches A via B
        b.engine.handleIncomingFrame(ack)
        val ackRelay = b.emitted.filter { it.type == MessageType.RELAY }.last()
        a.engine.handleIncomingFrame(ackRelay)
        assertEquals(listOf(ID_C), a.peersFound)
    }

    @Test
    fun findPeer_unknownTarget_noAck_noFound() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val b = makeEngine(ID_B, MeshCrypto.generateKeyPair())

        a.engine.sendFindPeer(ID_C) // C is not on the network
        b.engine.handleIncomingFrame(a.emitted.last())

        // B relays (the search continues), but there is no ACK
        assertEquals(1, b.emitted.filter { it.type == MessageType.RELAY }.size)
        assertTrue(b.emitted.none { it.type == MessageType.FIND_PEER_ACK })
        assertTrue(b.peersFound.isEmpty())
    }

    @Test
    fun findPeer_loopback_ownRequest_dropped() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())

        a.engine.sendFindPeer(ID_C)
        val find = a.emitted.last()
        a.emitted.clear()

        // Our own search returning over the flood is not relayed again
        a.engine.handleIncomingFrame(find)
        assertTrue(a.emitted.isEmpty())
        assertEquals(1L, a.engine.stats()["duplicatesDropped"])
    }

    // ---------------- Phase 5: Store-and-forward ----------------

    /** Builds a DELIVERY_REPORT frame that reaches A (direct). */
    private fun makeDeliveryReport(fromId: String, forMsgId: String, delivered: Boolean): MeshFrame {
        val idBytes = forMsgId.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(1 + idBytes.size)
        payload[0] = if (delivered) 0x01 else 0x00
        idBytes.copyInto(payload, 1)
        return MeshFrame(
            type = MessageType.DELIVERY_REPORT,
            hopLimit = 2,
            ttl = 6,
            encrypted = false,
            senderId = fromId,
            targetId = ID_A,
            msgSeq = System.currentTimeMillis(),
            payload = payload,
            senderPublicKey = null,
        )
    }

    @Test
    fun sendText_queuesInOutbox_untilDelivered() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyB = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, keyB)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        b.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        // Message is sent -> queued
        val msgId = a.engine.sendText(ID_B, "undelivered")
        assertNotNull(msgId)
        assertEquals(listOf(msgId to "queued"), a.outboxStatus)
        assertEquals(1, a.engine.outboxSnapshot().size)

        // Real flow: B receives and sends a delivery report
        b.engine.handleIncomingFrame(a.emitted.last())
        val report = b.emitted.last()
        assertEquals(MessageType.DELIVERY_REPORT, report.type)

        // A receives the report: removed from the queue ("delivered")
        a.engine.handleIncomingFrame(report)
        assertTrue(a.engine.outboxSnapshot().isEmpty())
        assertEquals(listOf(msgId to "queued", msgId to "delivered"), a.outboxStatus)
        assertEquals(listOf(msgId to true), a.deliveryReports)
    }

    @Test
    fun retryPending_reshootsQueuedFrames_toAllOrTarget() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyB = MeshCrypto.generateKeyPair()
        val keyC = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        a.peerStore.markAuthorized(ID_C, MeshCrypto.b64(keyC.publicKey))

val m1 = a.engine.sendText(ID_B, "for B")
        val m2 = a.engine.sendText(ID_C, "for C")
        a.emitted.clear()
        val queuedCount = a.outboxStatus.size // 2 "queued" entries

        // Retry only for B -> 1 frame
        a.engine.retryPending(ID_B)
        assertEquals(1, a.emitted.size)
        assertEquals(ID_B, a.emitted.first().targetId)

        // Rate limit: a second retry soon after does not work (cooldown)
        a.emitted.clear()
        a.engine.retryPending(null)
        assertEquals(0, a.emitted.size) // rate-limited, nothing sent

        // Nothing is removed from the queue and the status does not change
        assertEquals(2, a.engine.outboxSnapshot().size)
        assertEquals(queuedCount, a.outboxStatus.size)
    }

    @Test
    fun expirePending_removesStale_afterTTL() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))

        // Send a fresh message — msgSeq is the current time
        val msgId = a.engine.sendText(ID_B, "very old")
        assertEquals(1, a.engine.outboxSnapshot().size)
        a.outboxStatus.clear()

        // expirePending: if called after OUTBOX_TTL_MS + 1s, the frame is expired
        val futureNow = System.currentTimeMillis() + RoutingEngine.OUTBOX_TTL_MS + 1000
        a.engine.expirePending(futureNow)

        assertTrue(a.engine.outboxSnapshot().isEmpty())
        assertEquals(listOf(msgId to "expired"), a.outboxStatus)
    }

    @Test
    fun restoreOutbox_expiresStaleImmediately() {
        val keyA = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))

        // Send a fresh message
        val freshId = a.engine.sendText(ID_B, "restore")
        val snapshot = a.engine.outboxSnapshot()
        assertEquals(1, snapshot.size)

        // Old frame: msgSeq older than 24 hours
        val staleId = "stale-msg-id"
        val staleFrame = snapshot.single().second.copy(
            msgSeq = System.currentTimeMillis() - RoutingEngine.OUTBOX_TTL_MS - 1000
        )

        // Restart simulation — fresh engine, fresh outbox
        val restarted = makeEngine(ID_A, keyA)
        restarted.peerStore.markAuthorized(ID_B, MeshCrypto.b64(
            MeshCrypto.generateKeyPair().publicKey))

        // Restore both — only the fresh one is kept
        restarted.engine.restoreOutbox(listOf(
            freshId!! to snapshot.single().second,
            staleId to staleFrame,
        ))
        assertEquals(1, restarted.engine.outboxSnapshot().size)
        assertEquals(freshId, restarted.engine.outboxSnapshot().first().first)
    }

    @Test
    fun restoreOutbox_recoversQueuedFrames() {
        val keyA = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))

        val msgId = a.engine.sendText(ID_B, "restore")
        val snapshot = a.engine.outboxSnapshot()
        assertEquals(1, snapshot.size)

        // Restart simulation: a fresh engine (same identity) restores the queue
        val restarted = makeEngine(ID_A, keyA)
        restarted.peerStore.markAuthorized(ID_B, MeshCrypto.b64(
            MeshCrypto.generateKeyPair().publicKey))
        restarted.engine.restoreOutbox(snapshot)

        assertEquals(1, restarted.engine.outboxSnapshot().size)
        restarted.engine.retryPending(ID_B)
        assertEquals(1, restarted.emitted.size)
        assertEquals(MessageType.TEXT, restarted.emitted.first().type)
    }

    // ---------------- Phase 6: Heartbeat / presence ----------------

    @Test
    fun sendPing_broadcastAndLoopbackProtected() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())

        a.engine.sendPing()
        assertEquals(1, a.emitted.size)
        val ping = a.emitted.first()
        assertEquals(MessageType.PEER_PING, ping.type)
        assertEquals(MeshFrame.BROADCAST, ping.targetId)
        assertFalse(ping.encrypted)

        // Even if our own ping returns, dedup prevents relay
        a.emitted.clear()
        a.engine.handleIncomingFrame(ping)
        assertTrue(a.emitted.isEmpty())
    }

    @Test
    fun incomingFrame_marksSenderSeen() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyB = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, keyB)

        // B is paired with A (A knows B), but B has not been seen yet
        b.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        // A sends a ping -> B is seen
        a.engine.sendPing()
        b.engine.handleIncomingFrame(a.emitted.last())
        assertNotNull(b.peerStore.get(ID_A))
        assertTrue(b.peerStore.get(ID_A)!!.lastSeenMs > 0)

        // A's own ping (broadcast) is seen by B but not relayed
        assertTrue(b.emitted.isEmpty())
    }

    @Test
    fun deliveryReport_forUnknownMsg_doesNotCrash() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.handleIncomingFrame(makeDeliveryReport(ID_B, "unknown", true))
        assertTrue(a.engine.outboxSnapshot().isEmpty())
        assertEquals(listOf("unknown" to true), a.deliveryReports)
    }

    @Test
    fun deliveryReport_failed_removesFromOutbox() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))

        val msgId = a.engine.sendText(ID_B, "not delivered")
        a.engine.handleIncomingFrame(makeDeliveryReport(ID_B, msgId!!, false))

        assertTrue(a.engine.outboxSnapshot().isEmpty())
        assertEquals(listOf(msgId to "queued", msgId to "delivered"), a.outboxStatus) // the failed one is also removed from the queue
        assertEquals(listOf(msgId to false), a.deliveryReports)
    }
}
