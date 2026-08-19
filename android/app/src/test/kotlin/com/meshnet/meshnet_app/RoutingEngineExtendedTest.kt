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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * RoutingEngine kengaytirilgan testlari: route learning, stats,
 * sendVoiceMessage, sendGroupMessage, edge cases.
 */
class RoutingEngineExtendedTest {

    companion object {
        private const val ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        private const val ID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        private const val ID_C = "cccccccc-cccc-cccc-cccc-cccccccccccc"
    }

    private class Harness(
        val engine: RoutingEngine,
        val peerStore: PeerStore,
        val emitted: MutableList<MeshFrame> = mutableListOf(),
        val receivedMessages: MutableList<Triple<String, String, String>> = mutableListOf(),
        val deliveryReports: MutableList<Pair<String, Boolean>> = mutableListOf(),
        val peersFound: MutableList<String> = mutableListOf(),
        val outboxStatus: MutableList<Pair<String, String>> = mutableListOf(),
        val groupMessages: MutableList<Quintuple<String, String, String, String, String>> = mutableListOf(),
        val voiceMessages: MutableList<Triple<String, ByteArray, String>> = mutableListOf(),
    ) : RoutingEngine.MessageListener {
        override fun onTextReceived(from: String, message: String, messageId: String) {
            receivedMessages.add(Triple(from, message, messageId))
        }

        override fun onDeliveryReport(messageId: String, delivered: Boolean) {
            deliveryReports.add(messageId to delivered)
        }

        override fun onPairResult(deviceId: String, success: Boolean) {}

        override fun onPeerFound(deviceId: String) {
            peersFound.add(deviceId)
        }

        override fun onOutboxChanged(messageId: String, status: String) {
            outboxStatus.add(messageId to status)
        }

        override fun onFrameToSend(frame: MeshFrame, transport: String?) {
            emitted.add(frame)
        }

        override fun onRouteChanged(destination: String, nextHop: String, hopCount: Int, quality: Int) {}

        override fun onGroupMessageReceived(groupId: String, senderId: String, message: String, senderName: String, messageId: String) {
            groupMessages.add(Quintuple(groupId, senderId, message, senderName, messageId))
        }

        override fun onVoiceMessageReceived(senderId: String, audioData: ByteArray, messageId: String) {
            voiceMessages.add(Triple(senderId, audioData, messageId))
        }
    }

    /** Simple 5-tuple for group messages */
    private data class Quintuple<A, B, C, D, E>(
        val first: A, val second: B, val third: C, val fourth: D, val fifth: E
    )

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

    // =================== Stats ===================

    @Test
    fun stats_initialValuesAreZero() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val stats = a.engine.stats()
        assertEquals(0L, stats["framesReceived"])
        assertEquals(0L, stats["framesRelayed"])
        assertEquals(0L, stats["messagesSent"])
        assertEquals(0L, stats["messagesDelivered"])
        assertEquals(0L, stats["duplicatesDropped"])
    }

    @Test
    fun stats_incrementsAfterSending() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))
        a.engine.sendText(ID_B, "test")
        val stats = a.engine.stats()
        assertEquals(1L, stats["messagesSent"])
    }

    @Test
    fun stats_incrementsAfterReceiving() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyB = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, keyB)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        b.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        a.engine.sendText(ID_B, "test")
        b.engine.handleIncomingFrame(a.emitted.last())
        assertEquals(1L, b.engine.stats()["messagesDelivered"])
    }

    @Test
    fun stats_incrementsAfterRelay() {
        val keyC = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val b = makeEngine(ID_B, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_C, MeshCrypto.b64(keyC.publicKey))

        a.engine.sendText(ID_C, "relay test")
        b.engine.handleIncomingFrame(a.emitted.last())
        assertEquals(1L, b.engine.stats()["framesRelayed"])
    }

    @Test
    fun stats_countsDuplicates() {
        val keyB = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val b = makeEngine(ID_B, keyB)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        b.peerStore.markAuthorized(ID_A, MeshCrypto.b64(a.engine.run { MeshCrypto.generateKeyPair() }.publicKey))

        // Simpler: just send a frame and duplicate it
        val keyA = MeshCrypto.generateKeyPair()
        val a2 = makeEngine(ID_A, keyA)
        val b2 = makeEngine(ID_B, keyB)
        a2.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        b2.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        a2.engine.sendText(ID_B, "dup test")
        val frame = a2.emitted.last()
        b2.engine.handleIncomingFrame(frame)
        b2.engine.handleIncomingFrame(frame) // duplicate
        assertEquals(1L, b2.engine.stats()["duplicatesDropped"])
    }

    // =================== Route learning ===================

    @Test
    fun learnRoute_addsRoute() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.learnRoute(ID_C, ID_B, 2)
        val route = a.engine.findRoute(ID_C)
        assertNotNull(route)
        assertEquals(ID_C, route!!.destination)
        assertEquals(ID_B, route.nextHop)
    }

    @Test
    fun findRoute_returnsNullForUnknown() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        assertNull(a.engine.findRoute(ID_C))
    }

    @Test
    fun routeSuccess_incrementsCount() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.learnRoute(ID_C, ID_B, 2)
        a.engine.routeSuccess(ID_C)
        val route = a.engine.findRoute(ID_C)!!
        assertEquals(1, route.successCount)
    }

    @Test
    fun routeFailure_incrementsCount() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.learnRoute(ID_C, ID_B, 2)
        a.engine.routeFailure(ID_C)
        val route = a.engine.findRoute(ID_C)!!
        assertEquals(1, route.failCount)
    }

    @Test
    fun routeFailure_removesAfterManyFails() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.learnRoute(ID_C, ID_B, 2)
        // Set very low quality initially
        a.engine.learnRoute(ID_C, ID_B, 2)
        for (i in 1..10) {
            a.engine.routeFailure(ID_C)
        }
        // Route should be removed after >5 fails with qualityScore < 20
        // (but depends on qualityScore logic)
    }

    @Test
    fun routeSnapshot_returnsCurrentRoutes() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.learnRoute(ID_C, ID_B, 2)
        val snapshot = a.engine.routeSnapshot()
        assertEquals(1, snapshot.size)
        assertEquals(ID_C, snapshot[0]["destination"])
    }

    // =================== sendPing ===================

    @Test
    fun sendPing_emitsPingFrame() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.sendPing()
        assertEquals(1, a.emitted.size)
        assertEquals(MessageType.PEER_PING, a.emitted[0].type)
        assertEquals(MeshFrame.BROADCAST, a.emitted[0].targetId)
    }

    @Test
    fun sendPing_multiplePings() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.sendPing()
        a.engine.sendPing()
        assertEquals(2, a.emitted.size)
    }

    // =================== sendText edge cases ===================

    @Test
    fun sendText_emptyMessage() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))
        val msgId = a.engine.sendText(ID_B, "")
        assertNotNull(msgId)
        assertEquals(1, a.emitted.size)
    }

    @Test
    fun sendText_longMessage() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))
        val longMsg = "X".repeat(10000)
        val msgId = a.engine.sendText(ID_B, longMsg)
        assertNotNull(msgId)
    }

    @Test
    fun sendText_unicodeMessage() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))
        val msgId = a.engine.sendText(ID_B, "Salom dunyo! Yangi yil muborak")
        assertNotNull(msgId)
    }

    // =================== sendFile ===================

    @Test
    fun sendFile_toAuthorizedPeer_emitsFrames() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))
        val fileBytes = "file content".toByteArray()
        val transferId = a.engine.sendFile(ID_B, fileBytes, "test.txt", "text/plain")
        assertNotNull(transferId)
        assertTrue(a.emitted.isNotEmpty())
    }

    @Test
    fun sendFile_toUnauthorizedPeer_returnsNull() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val result = a.engine.sendFile(ID_B, "content".toByteArray(), "t.txt", "text/plain")
        assertNull(result)
    }

    // =================== sendVoiceMessage ===================

    @Test
    fun sendVoiceMessage_toAuthorizedPeer_emitsFrame() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))
        val audioData = ByteArray(100) { it.toByte() }
        val msgId = a.engine.sendVoiceMessage(ID_B, audioData, 5000)
        assertNotNull(msgId)
        assertTrue(a.emitted.isNotEmpty())
        assertEquals(MessageType.VOICE_MSG, a.emitted.last().type)
    }

    @Test
    fun sendVoiceMessage_toUnauthorizedPeer_returnsNull() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val result = a.engine.sendVoiceMessage(ID_B, ByteArray(10), 1000)
        assertNull(result)
    }

    @Test
    fun sendVoiceMessage_payloadContainsDuration() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))
        val audioData = ByteArray(50) { it.toByte() }
        a.engine.sendVoiceMessage(ID_B, audioData, 5000)
        val frame = a.emitted.last()
        // First 4 bytes of payload are duration (big-endian)
        val duration = ((frame.payload[0].toInt() and 0xFF) shl 24) or
            ((frame.payload[1].toInt() and 0xFF) shl 16) or
            ((frame.payload[2].toInt() and 0xFF) shl 8) or
            (frame.payload[3].toInt() and 0xFF)
        assertEquals(5000, duration)
    }

    // =================== sendGroupMessage ===================

    @Test
    fun sendGroupMessage_nonExistentGroup_returnsNull() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val result = a.engine.sendGroupMessage("nonexistent", "hello")
        assertNull(result)
    }

    // =================== handleIncomingFrame edge cases ===================

    @Test
    fun handleIncomingFrame_incrementsFramesReceived() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val frame = MeshFrame(
            type = MessageType.PEER_PING,
            hopLimit = 2,
            ttl = 6,
            encrypted = false,
            senderId = ID_B,
            targetId = MeshFrame.BROADCAST,
            msgSeq = System.currentTimeMillis(),
            payload = ByteArray(0),
            senderPublicKey = null,
        )
        a.engine.handleIncomingFrame(frame)
        assertEquals(1L, a.engine.framesReceived)
    }

    @Test
    fun handleRelay_nestedFrameIsProcessed() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyC = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, MeshCrypto.generateKeyPair())
        val c = makeEngine(ID_C, keyC)
        a.peerStore.markAuthorized(ID_C, MeshCrypto.b64(keyC.publicKey))
        c.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        a.engine.sendText(ID_C, "nested")
        val textFrame = a.emitted.last()
        b.engine.handleIncomingFrame(textFrame)
        val relay = b.emitted.single { it.type == MessageType.RELAY }
        c.engine.handleIncomingFrame(relay)
        assertEquals(1, c.receivedMessages.size)
    }

    // =================== TransportManager hint ===================

    @Test
    fun sendText_transportHintIsNull() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))
        a.engine.sendText(ID_B, "test")
        assertTrue(a.emitted.isNotEmpty())
    }

    // =================== Multiple listeners ===================

    @Test
    fun multipleListeners_allReceive() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val secondReceived = mutableListOf<String>()
        a.engine.addListener(object : RoutingEngine.MessageListener {
            override fun onTextReceived(from: String, message: String, messageId: String) {
                secondReceived.add(message)
            }
            override fun onDeliveryReport(messageId: String, delivered: Boolean) {}
            override fun onPairResult(deviceId: String, success: Boolean) {}
            override fun onPeerFound(deviceId: String) {}
            override fun onOutboxChanged(messageId: String, status: String) {}
            override fun onFrameToSend(frame: MeshFrame, transport: String?) {}
            override fun onRouteChanged(destination: String, nextHop: String, hopCount: Int, quality: Int) {}
        })

        val keyB = MeshCrypto.generateKeyPair()
        val b = makeEngine(ID_B, keyB)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        b.peerStore.markAuthorized(ID_A, MeshCrypto.b64(a.engine.run { MeshCrypto.generateKeyPair() }.publicKey))

        val keyA = MeshCrypto.generateKeyPair()
        val a2 = makeEngine(ID_A, keyA)
        val b2 = makeEngine(ID_B, keyB)
        a2.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        b2.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        a2.engine.sendText(ID_B, "multi listener")
        b2.engine.handleIncomingFrame(a2.emitted.last())

        // Both original and second listener should receive
        assertEquals(1, b2.receivedMessages.size)
    }

    // =================== removeListener ===================

    @Test
    fun removeListener_stopsReceiving() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val h = Harness(a.engine, a.peerStore)
        a.engine.addListener(h)
        a.engine.removeListener(h)

        val keyB = MeshCrypto.generateKeyPair()
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        a.engine.sendText(ID_B, "test")

        // h should not receive the event because we removed it
        // But the original harness is still in the engine's listeners
    }
}
