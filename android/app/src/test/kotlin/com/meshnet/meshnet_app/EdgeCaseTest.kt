package com.meshnet.meshnet_app

import android.content.Context
import android.content.SharedPreferences
import com.meshnet.meshnet_app.protocol.MeshFrame
import com.meshnet.meshnet_app.protocol.MessageType
import com.meshnet.meshnet_app.protocol.RoutingEngine
import com.meshnet.meshnet_app.crypto.MeshCrypto
import com.meshnet.meshnet_app.storage.PeerStore
import org.junit.Assert.assertArrayEquals
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
import java.util.UUID

class EdgeCaseTest {

    companion object {
        private const val ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        private const val ID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    }

    private class Harness(
        val engine: RoutingEngine,
        val peerStore: PeerStore,
        val emitted: MutableList<MeshFrame> = mutableListOf(),
        val receivedMessages: MutableList<Triple<String, String, String>> = mutableListOf(),
        val deliveryReports: MutableList<Pair<String, Boolean>> = mutableListOf(),
    ) : RoutingEngine.MessageListener {
        override fun onTextReceived(from: String, message: String, messageId: String) {
            receivedMessages.add(Triple(from, message, messageId))
        }
        override fun onDeliveryReport(messageId: String, delivered: Boolean) {
            deliveryReports.add(messageId to delivered)
        }
        override fun onPairResult(deviceId: String, success: Boolean) {}
        override fun onPeerFound(deviceId: String) {}
        override fun onOutboxChanged(messageId: String, status: String) {}
        override fun onRouteChanged(destination: String, nextHop: String, hopCount: Int, quality: Int) {}
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

    @Test
    fun meshFrame_emptySenderId_decodeReturnsUnknown() {
        val frame = MeshFrame(
            type = MessageType.TEXT, hopLimit = 1, ttl = 1, encrypted = false,
            senderId = "", targetId = ID_B, msgSeq = 1, payload = ByteArray(0),
            senderPublicKey = null,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))
        assertNotNull(decoded)
    }

    @Test
    fun meshFrame_emptyPayload_roundtrip() {
        val frame = MeshFrame(
            type = MessageType.TEXT, hopLimit = 1, ttl = 1, encrypted = false,
            senderId = ID_A, targetId = ID_B, msgSeq = 0, payload = ByteArray(0),
            senderPublicKey = null,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun meshFrame_largePayload_50KB_roundtrip() {
        val largePayload = ByteArray(50_000) { (it % 256).toByte() }
        val frame = MeshFrame(
            type = MessageType.TEXT, hopLimit = 4, ttl = 6, encrypted = true,
            senderId = ID_A, targetId = ID_B, msgSeq = 999, payload = largePayload,
            senderPublicKey = null,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertArrayEquals(largePayload, decoded.payload)
    }

    @Test
    fun meshFrame_allNullBytes_payload() {
        val frame = MeshFrame(
            type = MessageType.TEXT, hopLimit = 1, ttl = 1, encrypted = false,
            senderId = ID_A, targetId = ID_B, msgSeq = 1, payload = ByteArray(100),
            senderPublicKey = null,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertTrue(decoded.payload.all { it == 0.toByte() })
    }

    @Test
    fun meshFrame_hopLimitMax_value() {
        val frame = MeshFrame(
            type = MessageType.RELAY, hopLimit = 127, ttl = 127, encrypted = false,
            senderId = ID_A, targetId = ID_B, msgSeq = 1, payload = ByteArray(0),
            senderPublicKey = null,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(127, decoded.hopLimit)
    }

    @Test
    fun meshFrame_hopLimitZero_roundtrip() {
        val frame = MeshFrame(
            type = MessageType.TEXT, hopLimit = 0, ttl = 6, encrypted = false,
            senderId = ID_A, targetId = ID_B, msgSeq = 1, payload = ByteArray(0),
            senderPublicKey = null,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(0, decoded.hopLimit)
    }

    @Test
    fun meshFrame_allMessageTypes_encodeDecode() {
        for (type in MessageType.entries) {
            val frame = MeshFrame(
                type = type, hopLimit = 1, ttl = 1, encrypted = false,
                senderId = ID_A, targetId = ID_B, msgSeq = 1, payload = ByteArray(0),
                senderPublicKey = null,
            )
            val decoded = MeshFrame.decode(MeshFrame.encode(frame))
            assertNotNull("Failed for type: $type", decoded)
            assertEquals(type, decoded!!.type)
        }
    }

    @Test
    fun meshFrame_broadcastTarget_decodesAsBroadcast() {
        val frame = MeshFrame(
            type = MessageType.PEER_PING, hopLimit = 1, ttl = 1, encrypted = false,
            senderId = ID_A, targetId = MeshFrame.BROADCAST, msgSeq = 1, payload = ByteArray(0),
            senderPublicKey = null,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(MeshFrame.BROADCAST, decoded.targetId)
    }

    @Test
    fun meshFrame_pairReqWithPublicKey_preservesKey() {
        val pk = ByteArray(32) { (it + 1).toByte() }
        val frame = MeshFrame(
            type = MessageType.PAIR_REQ, hopLimit = 2, ttl = 6, encrypted = false,
            senderId = ID_A, targetId = ID_B, msgSeq = 10, payload = ByteArray(0),
            senderPublicKey = pk,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertNotNull(decoded.senderPublicKey)
        assertArrayEquals(pk, decoded.senderPublicKey)
    }

    @Test
    fun meshFrame_pairAckWithPublicKey_preservesKey() {
        val pk = ByteArray(32) { (it * 3).toByte() }
        val frame = MeshFrame(
            type = MessageType.PAIR_ACK, hopLimit = 2, ttl = 6, encrypted = false,
            senderId = ID_A, targetId = ID_B, msgSeq = 10, payload = ByteArray(0),
            senderPublicKey = pk,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertArrayEquals(pk, decoded.senderPublicKey)
    }

    @Test
    fun meshFrame_textFrame_noPublicKey() {
        val frame = MeshFrame(
            type = MessageType.TEXT, hopLimit = 2, ttl = 6, encrypted = true,
            senderId = ID_A, targetId = ID_B, msgSeq = 1, payload = ByteArray(10),
            senderPublicKey = null,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertNull(decoded.senderPublicKey)
    }

    @Test
    fun meshFrame_msgSeq_maxLong() {
        val frame = MeshFrame(
            type = MessageType.TEXT, hopLimit = 1, ttl = 1, encrypted = false,
            senderId = ID_A, targetId = ID_B, msgSeq = Long.MAX_VALUE, payload = ByteArray(0),
            senderPublicKey = null,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(Long.MAX_VALUE, decoded.msgSeq)
    }

    @Test
    fun meshFrame_msgSeq_zero() {
        val frame = MeshFrame(
            type = MessageType.TEXT, hopLimit = 1, ttl = 1, encrypted = false,
            senderId = ID_A, targetId = ID_B, msgSeq = 0, payload = ByteArray(0),
            senderPublicKey = null,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(0L, decoded.msgSeq)
    }

    @Test
    fun uuid_formatValidation_validUUID() {
        val uuid = UUID.randomUUID().toString()
        val parts = uuid.split("-")
        assertEquals(5, parts.size)
        assertEquals(8, parts[0].length)
        assertEquals(4, parts[1].length)
        assertEquals(4, parts[2].length)
        assertEquals(4, parts[3].length)
        assertEquals(12, parts[4].length)
    }

    @Test
    fun uuid_parseInvalidString_throwsException() {
        try {
            UUID.fromString("not-a-uuid")
            assertTrue("Expected exception", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun routingEngine_seenCache_overflow_noException() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        for (i in 1..2000) {
            a.engine.handleIncomingFrame(MeshFrame(
                type = MessageType.TEXT, hopLimit = 1, ttl = 1, encrypted = false,
                senderId = "11111111-1111-1111-1111-${i.toString().padStart(12, '0')}",
                targetId = ID_A, msgSeq = i.toLong(), payload = "x".toByteArray(),
                senderPublicKey = null,
            ))
        }
    }

    @Test
    fun routingEngine_duplicateMessage_deduplication() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val frame = MeshFrame(
            type = MessageType.TEXT, hopLimit = 1, ttl = 1, encrypted = false,
            senderId = ID_B, targetId = ID_A, msgSeq = 1L, payload = "x".toByteArray(),
            senderPublicKey = null,
        )
        a.engine.handleIncomingFrame(frame)
        a.engine.handleIncomingFrame(frame)
        a.engine.handleIncomingFrame(frame)
        assertEquals(3L, a.engine.framesReceived)
        assertEquals(2L, a.engine.duplicatesDropped)
    }

    @Test
    fun meshFrame_corruptedMagicBytes_decodeReturnsNull() {
        val bytes = ByteArray(60) { 0x00 }
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xFF.toByte()
        assertNull(MeshFrame.decode(bytes))
    }

    @Test
    fun meshFrame_tooShortBytes_decodeReturnsNull() {
        assertNull(MeshFrame.decode(ByteArray(10)))
    }

    @Test
    fun meshFrame_exactHeaderSize_emptyPayload() {
        val frame = MeshFrame(
            type = MessageType.TEXT, hopLimit = 1, ttl = 1, encrypted = false,
            senderId = ID_A, targetId = ID_B, msgSeq = 1, payload = ByteArray(0),
            senderPublicKey = null,
        )
        val encoded = MeshFrame.encode(frame)
        assertEquals(MeshFrame.HEADER_SIZE, encoded.size)
    }

    @Test
    fun meshFrame_headerSizePlusPayload() {
        val payload = ByteArray(100) { 0x42 }
        val frame = MeshFrame(
            type = MessageType.TEXT, hopLimit = 1, ttl = 1, encrypted = false,
            senderId = ID_A, targetId = ID_B, msgSeq = 1, payload = payload,
            senderPublicKey = null,
        )
        val encoded = MeshFrame.encode(frame)
        assertEquals(MeshFrame.HEADER_SIZE + 100, encoded.size)
    }

    @Test
    fun meshFrame_headerSizePlusPublicKey32() {
        val pk = ByteArray(32) { 0x01 }
        val frame = MeshFrame(
            type = MessageType.PAIR_REQ, hopLimit = 1, ttl = 1, encrypted = false,
            senderId = ID_A, targetId = ID_B, msgSeq = 1, payload = ByteArray(0),
            senderPublicKey = pk,
        )
        val encoded = MeshFrame.encode(frame)
        assertEquals(MeshFrame.HEADER_SIZE + 32, encoded.size)
    }

    @Test
    fun meshFrame_constants_magicValues() {
        assertEquals(0x4D.toByte(), MeshFrame.MAGIC1)
        assertEquals(0x4E.toByte(), MeshFrame.MAGIC2)
    }

    @Test
    fun meshFrame_constants_versionOne() {
        assertEquals(0x01, MeshFrame.VERSION)
    }

    @Test
    fun meshFrame_constants_broadcastString() {
        assertEquals("broadcast", MeshFrame.BROADCAST)
    }

    @Test
    fun meshFrame_constants_headerSize() {
        assertEquals(47, MeshFrame.HEADER_SIZE)
    }

    @Test
    fun meshFrame_constants_idBytes() {
        assertEquals(16, MeshFrame.ID_BYTES)
    }

    @Test
    fun messageType_allCodesUnique() {
        val codes = MessageType.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun messageType_fromCode_valid() {
        assertEquals(MessageType.TEXT, MessageType.fromCode(0x02))
        assertEquals(MessageType.PEER_PING, MessageType.fromCode(0x01))
    }

    @Test
    fun messageType_fromCode_invalid_returnsNull() {
        assertNull(MessageType.fromCode(0x00))
        assertNull(MessageType.fromCode(0xFF.toByte()))
    }

    @Test
    fun meshFrame_findPeerAckWithPublicKey_preservesKey() {
        val pk = ByteArray(32) { (it * 7).toByte() }
        val frame = MeshFrame(
            type = MessageType.FIND_PEER_ACK, hopLimit = 4, ttl = 6, encrypted = false,
            senderId = ID_A, targetId = ID_B, msgSeq = 20, payload = ByteArray(0),
            senderPublicKey = pk,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertArrayEquals(pk, decoded.senderPublicKey)
    }

    @Test
    fun meshFrame_veryLargePayload_100KB() {
        val payload = ByteArray(100_000) { (it % 256).toByte() }
        val frame = MeshFrame(
            type = MessageType.TEXT, hopLimit = 4, ttl = 6, encrypted = true,
            senderId = ID_A, targetId = ID_B, msgSeq = 999, payload = payload,
            senderPublicKey = null,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(100_000, decoded.payload.size)
    }
}
