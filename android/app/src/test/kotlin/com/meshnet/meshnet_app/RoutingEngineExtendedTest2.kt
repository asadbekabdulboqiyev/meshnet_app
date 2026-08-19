package com.meshnet.meshnet_app

import android.content.Context
import android.content.SharedPreferences
import com.meshnet.meshnet_app.crypto.MeshCrypto
import com.meshnet.meshnet_app.protocol.MeshFrame
import com.meshnet.meshnet_app.protocol.MessageType
import com.meshnet.meshnet_app.protocol.RoutingEngine
import com.meshnet.meshnet_app.storage.PeerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class RoutingEngineExtendedTest2 {

    companion object {
        private const val ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        private const val ID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        private const val ID_C = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        private const val ID_D = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        private const val ID_M = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
    }

    private class Harness(
        val engine: RoutingEngine,
        val peerStore: PeerStore,
        val emitted: MutableList<MeshFrame> = mutableListOf(),
        val receivedMessages: MutableList<Triple<String, String, String>> = mutableListOf(),
        val deliveryReports: MutableList<Pair<String, Boolean>> = mutableListOf(),
        val peersFound: MutableList<String> = mutableListOf(),
        val outboxStatus: MutableList<Pair<String, String>> = mutableListOf(),
        val routeChanges: MutableList<Triple<String, String, Int>> = mutableListOf(),
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
        override fun onRouteChanged(destination: String, nextHop: String, hopCount: Int, quality: Int) {
            routeChanges.add(Triple(destination, nextHop, hopCount))
        }
        override fun onFrameToSend(frame: MeshFrame, transport: String?) { emitted.add(frame) }
    }

    private fun mockPrefs(): SharedPreferences {
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(editor.putString(anyString(), any())).thenReturn(editor)
        `when`(editor.remove(anyString())).thenReturn(editor)
        `when`(editor.clear()).thenReturn(editor)
        val prefs = mock(SharedPreferences::class.java)
        `when`(prefs.getString(anyString(), any())).thenReturn(null)
        `when`(prefs.edit()).thenReturn(editor)
        return prefs
    }

    private fun makeEngine(id: String, keyPair: MeshCrypto.KeyPair): Harness {
        val ctx = mock(Context::class.java)
        val prefs = mockPrefs()
        `when`(ctx.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
        val store = PeerStore(ctx)
        val engine = RoutingEngine(ctx, id, keyPair.privateKey, store)
        engine.setIdentityPublicKey(keyPair.publicKey)
        val harness = Harness(engine, store)
        engine.addListener(harness)
        return harness
    }

    private fun makeDeliveryReport(fromId: String, forMsgId: String, delivered: Boolean): MeshFrame {
        val idBytes = forMsgId.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(1 + idBytes.size)
        payload[0] = if (delivered) 0x01 else 0x00
        idBytes.copyInto(payload, 1)
        return MeshFrame(
            type = MessageType.DELIVERY_REPORT, hopLimit = 2, ttl = 6,
            encrypted = false, senderId = fromId, targetId = ID_A,
            msgSeq = System.currentTimeMillis(), payload = payload, senderPublicKey = null,
        )
    }

    @Test
    fun unicastVsFlood_floodWhenNoRoute() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_C, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))
        a.engine.sendText(ID_C, "flood")
        val frame = a.emitted.last()
        a.emitted.clear()
        val m = makeEngine(ID_M, MeshCrypto.generateKeyPair())
        m.engine.handleIncomingFrame(frame)
        assertTrue(m.emitted.isNotEmpty())
    }

    @Test
    fun relay_hopDecrement() {
        val keyC = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val b = makeEngine(ID_B, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_C, MeshCrypto.b64(keyC.publicKey))

        a.engine.sendText(ID_C, "hop test")
        val frame = a.emitted.last()
        assertEquals(4, frame.hopLimit)

        b.engine.handleIncomingFrame(frame)
        val relay = b.emitted.firstOrNull { it.type == MessageType.RELAY }
        assertNotNull(relay)
        assertEquals(3, relay!!.hopLimit)
    }

    @Test
    fun relay_ttlDecrement() {
        val keyC = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val b = makeEngine(ID_B, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_C, MeshCrypto.b64(keyC.publicKey))

        a.engine.sendText(ID_C, "ttl test")
        val frame = a.emitted.last()
        b.engine.handleIncomingFrame(frame)
        val relay = b.emitted.firstOrNull { it.type == MessageType.RELAY }
        assertNotNull(relay)
        assertEquals(5, relay!!.ttl)
    }

    @Test
    fun relay_hopZero_noRelay() {
        val b = makeEngine(ID_B, MeshCrypto.generateKeyPair())
        val frame = MeshFrame(
            type = MessageType.TEXT, hopLimit = 0, ttl = 6, encrypted = false,
            senderId = ID_A, targetId = ID_C, msgSeq = 1, payload = ByteArray(0),
            senderPublicKey = null,
        )
        b.engine.handleIncomingFrame(frame)
        assertTrue(b.emitted.isEmpty())
    }

    @Test
    fun relay_ttlZero_noRelay() {
        val b = makeEngine(ID_B, MeshCrypto.generateKeyPair())
        val frame = MeshFrame(
            type = MessageType.TEXT, hopLimit = 4, ttl = 0, encrypted = false,
            senderId = ID_A, targetId = ID_C, msgSeq = 1, payload = ByteArray(0),
            senderPublicKey = null,
        )
        b.engine.handleIncomingFrame(frame)
        assertTrue(b.emitted.isEmpty())
    }

    @Test
    fun stats_framesReceived_increments() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.handleIncomingFrame(MeshFrame(
            type = MessageType.TEXT, hopLimit = 1, ttl = 1, encrypted = false,
            senderId = ID_B, targetId = ID_A, msgSeq = 1, payload = ByteArray(0),
            senderPublicKey = null,
        ))
        assertEquals(1L, a.engine.stats()["framesReceived"])
    }

    @Test
    fun stats_messagesSent_increments() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))
        a.engine.sendText(ID_B, "hi")
        assertEquals(1L, a.engine.stats()["messagesSent"])
    }

    @Test
    fun stats_duplicatesDropped_increments() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val frame = MeshFrame(
            type = MessageType.TEXT, hopLimit = 1, ttl = 1, encrypted = false,
            senderId = ID_B, targetId = ID_A, msgSeq = 1, payload = ByteArray(0),
            senderPublicKey = null,
        )
        a.engine.handleIncomingFrame(frame)
        a.engine.handleIncomingFrame(frame)
        assertEquals(1L, a.engine.stats()["duplicatesDropped"])
    }

    @Test
    fun routeQuality_scoreCalculation() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.learnRoute(ID_B, ID_B, 1, 80)
        repeat(3) { a.engine.routeSuccess(ID_B) }
        val route = a.engine.findRoute(ID_B)!!
        assertTrue(route.qualityScore() > 50)
    }

    @Test
    fun routeQuality_allFail_lowScore() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.learnRoute(ID_B, ID_B, 1, 30)
        repeat(10) { a.engine.routeFailure(ID_B) }
        val route = a.engine.findRoute(ID_B)
        assertNull(route)
    }

    @Test
    fun routeExpiry_freshRoute_exists() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.learnRoute(ID_B, ID_B, 1, 80)
        assertNotNull(a.engine.findRoute(ID_B))
    }

    @Test
    fun routeLearning_improvesQuality() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.learnRoute(ID_B, ID_B, 1, 50)
        a.engine.learnRoute(ID_B, ID_B, 1, 90)
        val route = a.engine.findRoute(ID_B)!!
        assertTrue(route.linkQuality >= 50)
    }

    @Test
    fun routeSnapshot_containsAllFields() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.learnRoute(ID_B, ID_B, 2, 75)
        val snap = a.engine.routeSnapshot()
        assertEquals(1, snap.size)
        val r = snap[0]
        assertNotNull(r["destination"])
        assertNotNull(r["nextHop"])
        assertNotNull(r["hopCount"])
        assertNotNull(r["quality"])
        assertNotNull(r["age"])
        assertNotNull(r["success"])
        assertNotNull(r["fail"])
    }

    @Test
    fun routeTable_maxCapacity_enforced() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        for (i in 1..250) {
            a.engine.learnRoute("dest-$i", "hop-$i", 1, 50)
        }
        assertTrue(a.engine.routeSnapshot().size <= 210)
    }

    @Test
    fun retryPending_afterInterval_works() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))
        a.engine.sendText(ID_B, "retry")
        a.emitted.clear()
        a.engine.retryPending(ID_B)
        a.engine.retryPending(ID_B)
    }

    @Test
    fun outbox_maxCapacity_evicts() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))
        for (i in 1..505) {
            a.engine.sendText(ID_B, "msg-$i")
        }
        assertTrue(a.engine.outboxSnapshot().size <= RoutingEngine.MAX_OUTBOX)
    }

    @Test
    fun expirePending_removesStaleMessages() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))
        a.engine.sendText(ID_B, "old msg")
        val futureNow = System.currentTimeMillis() + RoutingEngine.OUTBOX_TTL_MS + 1000
        a.engine.expirePending(futureNow)
        assertTrue(a.engine.outboxSnapshot().isEmpty())
    }

    @Test
    fun expireRoutes_removesAllExpired() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.learnRoute(ID_B, ID_B, 1, 80)
        a.engine.learnRoute(ID_C, ID_C, 1, 80)
        a.engine.expireRoutes()
        // Fresh routes won't be expired
    }

    @Test
    fun sendPing_emitsFrame() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.sendPing()
        assertEquals(1, a.emitted.size)
        assertEquals(MessageType.PEER_PING, a.emitted[0].type)
    }

    @Test
    fun sendPing_broadcastTarget() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.sendPing()
        assertEquals(MeshFrame.BROADCAST, a.emitted[0].targetId)
    }

    @Test
    fun sendFindPeer_emitsFindPeerFrame() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val result = a.engine.sendFindPeer(ID_B)
        assertTrue(result)
        assertEquals(MessageType.FIND_PEER, a.emitted[0].type)
    }

    @Test
    fun sendFindPeer_emptyTarget_returnsFalse() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        assertFalse(a.engine.sendFindPeer(""))
    }

    @Test
    fun handleFindPeer_targetRepliesAck() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val c = makeEngine(ID_C, MeshCrypto.generateKeyPair())
        a.engine.sendFindPeer(ID_C)
        c.engine.handleIncomingFrame(a.emitted.last())
        val ack = c.emitted.last()
        assertEquals(MessageType.FIND_PEER_ACK, ack.type)
        assertEquals(ID_A, ack.targetId)
    }

    @Test
    fun handlePairReq_sendsPairAck() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val keyB = MeshCrypto.generateKeyPair()
        val req = MeshFrame(
            type = MessageType.PAIR_REQ, hopLimit = 4, ttl = 6, encrypted = false,
            senderId = ID_B, targetId = ID_A, msgSeq = 1, payload = ByteArray(0),
            senderPublicKey = keyB.publicKey,
        )
        a.engine.handleIncomingFrame(req)
        assertTrue(a.emitted.any { it.type == MessageType.PAIR_ACK })
    }

    @Test
    fun handlePairAck_addsToAuthorized() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val keyB = MeshCrypto.generateKeyPair()
        val ack = MeshFrame(
            type = MessageType.PAIR_ACK, hopLimit = 4, ttl = 6, encrypted = false,
            senderId = ID_B, targetId = ID_A, msgSeq = 1, payload = ByteArray(0),
            senderPublicKey = keyB.publicKey,
        )
        a.engine.handleIncomingFrame(ack)
        assertNotNull(a.peerStore.authorized(ID_B))
    }

    @Test
    fun sendText_emptyMessage_returnsMsgId() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))
        val msgId = a.engine.sendText(ID_B, "")
        assertNotNull(msgId)
    }

    @Test
    fun sendText_unauthorizedPeer_returnsNull() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        assertNull(a.engine.sendText(ID_B, "hello"))
    }

    @Test
    fun learnedRoute_selfIgnored() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.learnRoute(ID_A, ID_A, 1, 80)
        assertNull(a.engine.findRoute(ID_A))
    }

    @Test
    fun stats_seenCacheSize_increments() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val before = a.engine.stats()["seenCacheSize"] ?: 0L
        a.engine.handleIncomingFrame(MeshFrame(
            type = MessageType.TEXT, hopLimit = 1, ttl = 1, encrypted = false,
            senderId = ID_B, targetId = ID_A, msgSeq = 1, payload = ByteArray(0),
            senderPublicKey = null,
        ))
        assertTrue((a.engine.stats()["seenCacheSize"] ?: 0L) > before)
    }

    @Test
    fun sendText_validMessage_emitsEncryptedFrame() {
        val keyB = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        a.engine.sendText(ID_B, "test")
        val frame = a.emitted.last()
        assertTrue(frame.encrypted)
        assertEquals(MessageType.TEXT, frame.type)
    }

    @Test
    fun routeLearning_linkQualityAveraged() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.learnRoute(ID_B, ID_B, 1, 40)
        a.engine.learnRoute(ID_B, ID_B, 1, 80)
        val route = a.engine.findRoute(ID_B)!!
        assertEquals(60, route.linkQuality)
    }

    @Test
    fun outbox_queuedStatus_emitted() {
        val keyB = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        a.engine.sendText(ID_B, "queued")
        assertTrue(a.outboxStatus.any { it.second == "queued" })
    }
}
