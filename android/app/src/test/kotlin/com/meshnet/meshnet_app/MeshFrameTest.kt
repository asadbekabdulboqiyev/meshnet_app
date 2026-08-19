package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.protocol.MeshFrame
import com.meshnet.meshnet_app.protocol.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MeshFrame wire-formati: encode/decode roundtrip. */
class MeshFrameTest {

    private val idA = "11111111-1111-1111-1111-111111111111"
    private val idB = "22222222-2222-2222-2222-222222222222"

    @Test
    fun encodeDecode_roundtrip() {
        val frame = MeshFrame(
            type = MessageType.TEXT,
            hopLimit = 2,
            ttl = 6,
            encrypted = true,
            senderId = idA,
            targetId = idB,
            msgSeq = 12345,
            payload = "salom mesh".toByteArray(Charsets.UTF_8),
            senderPublicKey = null,
        )

        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!

        assertEquals(MessageType.TEXT, decoded.type)
        assertEquals(2, decoded.hopLimit)
        assertEquals(6, decoded.ttl)
        assertTrue(decoded.encrypted)
        assertEquals(idA, decoded.senderId)
        assertEquals(idB, decoded.targetId)
        assertEquals(12345L, decoded.msgSeq)
        assertTrue(frame.payload.contentEquals(decoded.payload))
        assertNull(decoded.senderPublicKey)
    }

    @Test
    fun broadcastTarget_roundtrip() {
        val frame = MeshFrame(
            type = MessageType.PEER_PING,
            hopLimit = 1,
            ttl = 3,
            encrypted = false,
            senderId = idA,
            targetId = MeshFrame.BROADCAST,
            msgSeq = 7,
            payload = ByteArray(0),
            senderPublicKey = null,
        )

        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(MeshFrame.BROADCAST, decoded.targetId)
    }

    @Test
    fun pairingFrame_keepsSenderPublicKey() {
        val pk = ByteArray(32) { it.toByte() }
        val frame = MeshFrame(
            type = MessageType.PAIR_REQ,
            hopLimit = 2,
            ttl = 6,
            encrypted = false,
            senderId = idA,
            targetId = idB,
            msgSeq = 99,
            payload = ByteArray(0),
            senderPublicKey = pk,
        )

        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertTrue(pk.contentEquals(decoded.senderPublicKey))
    }

    @Test
    fun emptyPayload_roundtrip() {
        val frame = MeshFrame(
            type = MessageType.RELAY,
            hopLimit = 1,
            ttl = 1,
            encrypted = false,
            senderId = idA,
            targetId = idB,
            msgSeq = 5,
            payload = ByteArray(0),
            senderPublicKey = null,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun decodeRejectsGarbage() {
        // Bosh / kalta input
        assertNull(MeshFrame.decode(ByteArray(0)))
        assertNull(MeshFrame.decode(ByteArray(10)))
        // Magic to'g'ri kelmagan
        val bytes = ByteArray(60) { 0x55 }
        assertNull(MeshFrame.decode(bytes))
    }

    @Test
    fun corruptedPayloadChangesBytes() {
        // Frame formatida payload uzunligi ko'rsatilmagan (qolgan hammasi),
        // shuning uchun payloadning so'nggi baytini buzish decode'ni buzmaydi.
        // Yaxlitlik tekshiruvi AEAD tag'ida (MeshCryptoTest) amalga oshadi.
        val frame = MeshFrame(
            type = MessageType.TEXT,
            hopLimit = 2,
            ttl = 6,
            encrypted = true,
            senderId = idA,
            targetId = idB,
            msgSeq = 42,
            payload = ByteArray(64) { 0x01 },
            senderPublicKey = null,
        )
        val encoded = MeshFrame.encode(frame)
        encoded[encoded.size - 1] = 0x7F

        val decoded = MeshFrame.decode(encoded)!!
        assertEquals(frame.msgSeq, decoded.msgSeq)
        assertTrue(decoded.payload.last() == 0x7F.toByte())
    }
}
