package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.protocol.MeshFrame
import com.meshnet.meshnet_app.protocol.MessageType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshFrameStressTest {

    private val ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val ID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

    private fun createFrame(
        type: MessageType = MessageType.TEXT,
        hopLimit: Int = 4,
        ttl: Int = 6,
        encrypted: Boolean = true,
        senderId: String = ID_A,
        targetId: String = ID_B,
        msgSeq: Long = 1L,
        payload: ByteArray = ByteArray(10) { 0x42 },
        senderPublicKey: ByteArray? = null,
    ) = MeshFrame(type, hopLimit, ttl, encrypted, senderId, targetId, msgSeq, payload, senderPublicKey)

    @Test
    fun encodeDecode_100Cycles_stable() {
        var frame = createFrame()
        for (i in 1..100) {
            frame = MeshFrame.decode(MeshFrame.encode(frame))!!
        }
        assertEquals(MessageType.TEXT, frame.type)
        assertEquals(ID_A, frame.senderId)
        assertEquals(ID_B, frame.targetId)
    }

    @Test
    fun encodeDecode_100Cycles_payloadIntact() {
        val originalPayload = ByteArray(100) { (it % 256).toByte() }
        var frame = createFrame(payload = originalPayload)
        for (i in 1..100) {
            frame = MeshFrame.decode(MeshFrame.encode(frame))!!
        }
        assertArrayEquals(originalPayload, frame.payload)
    }

    @Test
    fun maxPayload_size() {
        val payload = ByteArray(65_000) { 0x01 }
        val frame = createFrame(payload = payload)
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(65_000, decoded.payload.size)
    }

    @Test
    fun allMessageTypes_encodeDecode() {
        for (type in MessageType.entries) {
            val frame = createFrame(type = type)
            val decoded = MeshFrame.decode(MeshFrame.encode(frame))
            assertNotNull("Type $type failed", decoded)
            assertEquals(type, decoded!!.type)
        }
    }

    @Test
    fun allMessageTypes_senderPreserved() {
        for (type in MessageType.entries) {
            val frame = createFrame(type = type)
            val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
            assertEquals(ID_A, decoded.senderId)
        }
    }

    @Test
    fun header_intactAfterEncodeDecode() {
        val frame = createFrame(hopLimit = 3, ttl = 5, encrypted = true, msgSeq = 42)
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(3, decoded.hopLimit)
        assertEquals(5, decoded.ttl)
        assertTrue(decoded.encrypted)
        assertEquals(42L, decoded.msgSeq)
    }

    @Test
    fun broadcastId_encodeDecode() {
        val frame = createFrame(targetId = MeshFrame.BROADCAST)
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(MeshFrame.BROADCAST, decoded.targetId)
    }

    @Test
    fun broadcastId_allZeroBytesInEncoded() {
        val frame = createFrame(targetId = MeshFrame.BROADCAST)
        val encoded = MeshFrame.encode(frame)
        val targetBytes = encoded.copyOfRange(23, 39)
        assertTrue(targetBytes.all { it == 0.toByte() })
    }

    @Test
    fun corruptedData_wrongMagic_returnsNull() {
        val frame = createFrame()
        val encoded = MeshFrame.encode(frame)
        encoded[0] = 0x00
        assertNull(MeshFrame.decode(encoded))
    }

    @Test
    fun corruptedData_truncatedHeader_returnsNull() {
        val frame = createFrame()
        val encoded = MeshFrame.encode(frame)
        assertNull(MeshFrame.decode(encoded.copyOf(20)))
    }

    @Test
    fun corruptedData_emptyArray_returnsNull() {
        assertNull(MeshFrame.decode(ByteArray(0)))
    }

    @Test
    fun corruptedData_singleByte_returnsNull() {
        assertNull(MeshFrame.decode(byteArrayOf(0x4D)))
    }

    @Test
    fun corruptedData_justMagicBytes_returnsNull() {
        assertNull(MeshFrame.decode(byteArrayOf(0x4D, 0x4E)))
    }

    @Test
    fun pairReqFrame_preservesPublicKey() {
        val pk = ByteArray(32) { (it + 10).toByte() }
        val frame = createFrame(type = MessageType.PAIR_REQ, senderPublicKey = pk)
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertNotNull(decoded.senderPublicKey)
        assertArrayEquals(pk, decoded.senderPublicKey)
    }

    @Test
    fun pairAckFrame_preservesPublicKey() {
        val pk = ByteArray(32) { (it * 5).toByte() }
        val frame = createFrame(type = MessageType.PAIR_ACK, senderPublicKey = pk)
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertArrayEquals(pk, decoded.senderPublicKey)
    }

    @Test
    fun textFrame_noPublicKey() {
        val frame = createFrame(type = MessageType.TEXT, senderPublicKey = null)
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertNull(decoded.senderPublicKey)
    }

    @Test
    fun relayFrame_noPublicKey() {
        val frame = createFrame(type = MessageType.RELAY, senderPublicKey = null)
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertNull(decoded.senderPublicKey)
    }

    @Test
    fun frameMsgSeq_variousValues() {
        for (seq in listOf(0L, 1L, 42L, 999999L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            val frame = createFrame(msgSeq = seq)
            val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
            assertEquals(seq, decoded.msgSeq)
        }
    }

    @Test
    fun frameHopLimit_variousValues() {
        for (hop in listOf(0, 1, 2, 4, 10, 127)) {
            val frame = createFrame(hopLimit = hop)
            val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
            assertEquals(hop, decoded.hopLimit)
        }
    }

    @Test
    fun frameTtl_variousValues() {
        for (ttl in listOf(0, 1, 3, 6, 10, 127)) {
            val frame = createFrame(ttl = ttl)
            val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
            assertEquals(ttl, decoded.ttl)
        }
    }

    @Test
    fun encryptedTrue_roundtrip() {
        val frame = createFrame(encrypted = true)
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertTrue(decoded.encrypted)
    }

    @Test
    fun encryptedFalse_roundtrip() {
        val frame = createFrame(encrypted = false)
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertFalse(decoded.encrypted)
    }

    @Test
    fun largePayload_50KB_intact() {
        val payload = ByteArray(50_000) { (it % 256).toByte() }
        val frame = createFrame(payload = payload)
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun emptyPayload_roundtrip() {
        val frame = createFrame(payload = ByteArray(0))
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun singleBytePayload_roundtrip() {
        val frame = createFrame(payload = byteArrayOf(0x42))
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(1, decoded.payload.size)
        assertEquals(0x42.toByte(), decoded.payload[0])
    }

    @Test
    fun encodedSize_matchesHeaderPlusPayloadPlusPubKey() {
        val payload = ByteArray(50) { 0x01 }
        val frame = createFrame(payload = payload)
        val encoded = MeshFrame.encode(frame)
        assertEquals(MeshFrame.HEADER_SIZE + 50, encoded.size)
    }

    @Test
    fun encodedSize_withPublicKey() {
        val pk = ByteArray(32) { 0x01 }
        val frame = createFrame(type = MessageType.PAIR_REQ, senderPublicKey = pk, payload = ByteArray(0))
        val encoded = MeshFrame.encode(frame)
        assertEquals(MeshFrame.HEADER_SIZE + 32, encoded.size)
    }

    @Test
    fun differentSenders_differentSenderBytes() {
        val f1 = createFrame(senderId = ID_A)
        val f2 = createFrame(senderId = ID_B)
        val e1 = MeshFrame.encode(f1)
        val e2 = MeshFrame.encode(f2)
        val sender1 = e1.copyOfRange(7, 23)
        val sender2 = e2.copyOfRange(7, 23)
        assertFalse(sender1.contentEquals(sender2))
    }

    @Test
    fun differentTargets_differentTargetBytes() {
        val f1 = createFrame(targetId = ID_A)
        val f2 = createFrame(targetId = ID_B)
        val e1 = MeshFrame.encode(f1)
        val e2 = MeshFrame.encode(f2)
        val target1 = e1.copyOfRange(23, 39)
        val target2 = e2.copyOfRange(23, 39)
        assertFalse(target1.contentEquals(target2))
    }

    @Test
    fun fileStartFrame_roundtrip() {
        val frame = createFrame(type = MessageType.FILE_START, payload = ByteArray(100) { 0x10 })
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(MessageType.FILE_START, decoded.type)
        assertEquals(100, decoded.payload.size)
    }

    @Test
    fun fileChunkFrame_roundtrip() {
        val frame = createFrame(type = MessageType.FILE_CHUNK, payload = ByteArray(200) { 0x11 })
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(MessageType.FILE_CHUNK, decoded.type)
    }

    @Test
    fun fileEndFrame_roundtrip() {
        val frame = createFrame(type = MessageType.FILE_END, payload = ByteArray(16) { 0x12 })
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(MessageType.FILE_END, decoded.type)
    }

    @Test
    fun voiceMsgFrame_roundtrip() {
        val frame = createFrame(type = MessageType.VOICE_MSG, payload = ByteArray(500) { 0x30 })
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(MessageType.VOICE_MSG, decoded.type)
    }

    @Test
    fun deliveryReportFrame_roundtrip() {
        val frame = createFrame(type = MessageType.DELIVERY_REPORT, payload = ByteArray(20) { 0x06 })
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(MessageType.DELIVERY_REPORT, decoded.type)
    }

    @Test
    fun groupMsgFrame_roundtrip() {
        val frame = createFrame(type = MessageType.GROUP_MSG, payload = ByteArray(50) { 0x21 })
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(MessageType.GROUP_MSG, decoded.type)
    }

    @Test
    fun ratchetInitFrame_roundtrip() {
        val pk = ByteArray(32) { 0x40 }
        val frame = createFrame(type = MessageType.PAIR_REQ, senderPublicKey = pk)
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(MessageType.PAIR_REQ, decoded.type)
        assertArrayEquals(pk, decoded.senderPublicKey)
    }
}
