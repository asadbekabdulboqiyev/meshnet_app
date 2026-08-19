package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.protocol.MeshFrame
import com.meshnet.meshnet_app.protocol.MessageType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MeshFrame kengaytirilgan testlari: edge cases, barcha message types,
 * header format, broadcast, valid/invalid input, UUID handling.
 */
class MeshFrameExtendedTest {

    private val idA = "11111111-1111-1111-1111-111111111111"
    private val idB = "22222222-2222-2222-2222-222222222222"

    // =================== Header format ===================

    @Test
    fun headerSize_is47Bytes() {
        assertEquals(47, MeshFrame.HEADER_SIZE)
    }

    @Test
    fun idBytes_is16() {
        assertEquals(16, MeshFrame.ID_BYTES)
    }

    @Test
    fun magic1_is0x4D() {
        assertEquals(0x4D.toByte(), MeshFrame.MAGIC1)
    }

    @Test
    fun magic2_is0x4E() {
        assertEquals(0x4E.toByte(), MeshFrame.MAGIC2)
    }

    @Test
    fun version_is0x01() {
        assertEquals(0x01, MeshFrame.VERSION)
    }

    @Test
    fun broadcast_isSpecialString() {
        assertEquals("broadcast", MeshFrame.BROADCAST)
    }

    // =================== Encode output format ===================

    @Test
    fun encode_minimumFrameSize() {
        val frame = MeshFrame(
            type = MessageType.PEER_PING,
            hopLimit = 1,
            ttl = 1,
            encrypted = false,
            senderId = idA,
            targetId = idB,
            msgSeq = 0,
            payload = ByteArray(0),
            senderPublicKey = null,
        )
        val encoded = MeshFrame.encode(frame)
        // HEADER_SIZE (47) + no pubkey + no payload = 47
        assertEquals(47, encoded.size)
    }

    @Test
    fun encode_withPayload_addsPayloadSize() {
        val payload = "hello world".toByteArray(Charsets.UTF_8)
        val frame = MeshFrame(
            type = MessageType.TEXT,
            hopLimit = 2,
            ttl = 6,
            encrypted = false,
            senderId = idA,
            targetId = idB,
            msgSeq = 1,
            payload = payload,
            senderPublicKey = null,
        )
        val encoded = MeshFrame.encode(frame)
        assertEquals(MeshFrame.HEADER_SIZE + payload.size, encoded.size)
    }

    @Test
    fun encode_withPublicKey_addsPublicKeySize() {
        val pk = ByteArray(32) { it.toByte() }
        val frame = MeshFrame(
            type = MessageType.PAIR_REQ,
            hopLimit = 2,
            ttl = 6,
            encrypted = false,
            senderId = idA,
            targetId = idB,
            msgSeq = 1,
            payload = ByteArray(0),
            senderPublicKey = pk,
        )
        val encoded = MeshFrame.encode(frame)
        assertEquals(MeshFrame.HEADER_SIZE + 32, encoded.size)
    }

    @Test
    fun encode_withPublicKeyAndPayload_addsBoth() {
        val pk = ByteArray(32) { it.toByte() }
        val payload = "data".toByteArray()
        val frame = MeshFrame(
            type = MessageType.PAIR_ACK,
            hopLimit = 2,
            ttl = 6,
            encrypted = false,
            senderId = idA,
            targetId = idB,
            msgSeq = 1,
            payload = payload,
            senderPublicKey = pk,
        )
        val encoded = MeshFrame.encode(frame)
        assertEquals(MeshFrame.HEADER_SIZE + 32 + payload.size, encoded.size)
    }

    @Test
    fun encode_firstTwoBytesAreMagic() {
        val frame = MeshFrame(
            type = MessageType.TEXT,
            hopLimit = 1,
            ttl = 1,
            encrypted = false,
            senderId = idA,
            targetId = idB,
            msgSeq = 0,
            payload = ByteArray(0),
            senderPublicKey = null,
        )
        val encoded = MeshFrame.encode(frame)
        assertEquals(MeshFrame.MAGIC1, encoded[0])
        assertEquals(MeshFrame.MAGIC2, encoded[1])
    }

    @Test
    fun encode_versionByte() {
        val frame = MeshFrame(
            type = MessageType.TEXT,
            hopLimit = 1,
            ttl = 1,
            encrypted = false,
            senderId = idA,
            targetId = idB,
            msgSeq = 0,
            payload = ByteArray(0),
            senderPublicKey = null,
        )
        val encoded = MeshFrame.encode(frame)
        assertEquals(MeshFrame.VERSION.toByte(), encoded[2])
    }

    @Test
    fun encode_typeByte() {
        val frame = MeshFrame(
            type = MessageType.VOICE_MSG,
            hopLimit = 1,
            ttl = 1,
            encrypted = false,
            senderId = idA,
            targetId = idB,
            msgSeq = 0,
            payload = ByteArray(0),
            senderPublicKey = null,
        )
        val encoded = MeshFrame.encode(frame)
        assertEquals(MessageType.VOICE_MSG.code, encoded[3])
    }

    @Test
    fun encode_hopLimitByte() {
        val frame = MeshFrame(
            type = MessageType.TEXT,
            hopLimit = 42,
            ttl = 1,
            encrypted = false,
            senderId = idA,
            targetId = idB,
            msgSeq = 0,
            payload = ByteArray(0),
            senderPublicKey = null,
        )
        val encoded = MeshFrame.encode(frame)
        assertEquals(42.toByte(), encoded[4])
    }

    @Test
    fun encode_ttlByte() {
        val frame = MeshFrame(
            type = MessageType.TEXT,
            hopLimit = 1,
            ttl = 99,
            encrypted = false,
            senderId = idA,
            targetId = idB,
            msgSeq = 0,
            payload = ByteArray(0),
            senderPublicKey = null,
        )
        val encoded = MeshFrame.encode(frame)
        assertEquals(99.toByte(), encoded[5])
    }

    @Test
    fun encode_encryptedFlag_1() {
        val frame = MeshFrame(
            type = MessageType.TEXT,
            hopLimit = 1,
            ttl = 1,
            encrypted = true,
            senderId = idA,
            targetId = idB,
            msgSeq = 0,
            payload = ByteArray(0),
            senderPublicKey = null,
        )
        val encoded = MeshFrame.encode(frame)
        assertEquals(0x01.toByte(), encoded[6])
    }

    @Test
    fun encode_encryptedFlag_0() {
        val frame = MeshFrame(
            type = MessageType.TEXT,
            hopLimit = 1,
            ttl = 1,
            encrypted = false,
            senderId = idA,
            targetId = idB,
            msgSeq = 0,
            payload = ByteArray(0),
            senderPublicKey = null,
        )
        val encoded = MeshFrame.encode(frame)
        assertEquals(0x00.toByte(), encoded[6])
    }

    // =================== Broadcast ===================

    @Test
    fun broadcast_encodesAsZeros() {
        val frame = MeshFrame(
            type = MessageType.PEER_PING,
            hopLimit = 1,
            ttl = 1,
            encrypted = false,
            senderId = idA,
            targetId = MeshFrame.BROADCAST,
            msgSeq = 0,
            payload = ByteArray(0),
            senderPublicKey = null,
        )
        val encoded = MeshFrame.encode(frame)
        // Target ID bytes (offset 23-38) should all be 0
        for (i in 23..38) {
            assertEquals(0x00.toByte(), encoded[i])
        }
    }

    @Test
    fun broadcast_decodesToBroadcastString() {
        val frame = MeshFrame(
            type = MessageType.TEXT,
            hopLimit = 1,
            ttl = 1,
            encrypted = false,
            senderId = idA,
            targetId = MeshFrame.BROADCAST,
            msgSeq = 0,
            payload = ByteArray(0),
            senderPublicKey = null,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(MeshFrame.BROADCAST, decoded.targetId)
    }

    // =================== All message types ===================

    @Test
    fun encodeDecode_allMessageTypes() {
        for (type in MessageType.entries) {
            val frame = MeshFrame(
                type = type,
                hopLimit = 2,
                ttl = 6,
                encrypted = false,
                senderId = idA,
                targetId = idB,
                msgSeq = 42,
                payload = "test".toByteArray(),
                senderPublicKey = null,
            )
            val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
            assertEquals("Type $type failed", type, decoded.type)
        }
    }

    // =================== Sender public key ===================

    @Test
    fun senderPublicKey_onlyForPairReq() {
        val pk = ByteArray(32) { 0x0A }
        val frame = MeshFrame(
            type = MessageType.PAIR_REQ,
            hopLimit = 2,
            ttl = 6,
            encrypted = false,
            senderId = idA,
            targetId = idB,
            msgSeq = 1,
            payload = ByteArray(0),
            senderPublicKey = pk,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertNotNull(decoded.senderPublicKey)
        assertArrayEquals(pk, decoded.senderPublicKey)
    }

    @Test
    fun senderPublicKey_forPairAck() {
        val pk = ByteArray(32) { 0x0B }
        val frame = MeshFrame(
            type = MessageType.PAIR_ACK,
            hopLimit = 2,
            ttl = 6,
            encrypted = false,
            senderId = idA,
            targetId = idB,
            msgSeq = 1,
            payload = ByteArray(0),
            senderPublicKey = pk,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertNotNull(decoded.senderPublicKey)
        assertArrayEquals(pk, decoded.senderPublicKey)
    }

    @Test
    fun senderPublicKey_forFindPeerAck() {
        val pk = ByteArray(32) { 0x0C }
        val frame = MeshFrame(
            type = MessageType.FIND_PEER_ACK,
            hopLimit = 2,
            ttl = 6,
            encrypted = false,
            senderId = idA,
            targetId = idB,
            msgSeq = 1,
            payload = ByteArray(0),
            senderPublicKey = pk,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertNotNull(decoded.senderPublicKey)
        assertArrayEquals(pk, decoded.senderPublicKey)
    }

    @Test
    fun senderPublicKey_notForText() {
        val pk = ByteArray(32) { 0x0D }
        val frame = MeshFrame(
            type = MessageType.TEXT,
            hopLimit = 2,
            ttl = 6,
            encrypted = false,
            senderId = idA,
            targetId = idB,
            msgSeq = 1,
            payload = ByteArray(0),
            senderPublicKey = pk,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertNull(decoded.senderPublicKey)
    }

    // =================== Decode rejection ===================

    @Test
    fun decode_emptyByteArray_returnsNull() {
        assertNull(MeshFrame.decode(ByteArray(0)))
    }

    @Test
    fun decode_oneByte_returnsNull() {
        assertNull(MeshFrame.decode(byteArrayOf(0x4D)))
    }

    @Test
    fun decode_headerOnly_returnsValidFrame() {
        val buf = ByteArray(MeshFrame.HEADER_SIZE)
        buf[0] = MeshFrame.MAGIC1
        buf[1] = MeshFrame.MAGIC2
        buf[2] = MeshFrame.VERSION.toByte()
        buf[3] = MessageType.PEER_PING.code
        buf[4] = 1 // hop
        buf[5] = 1 // ttl
        buf[6] = 0 // flags
        // sender/target default to all zeros -> broadcast
        val decoded = MeshFrame.decode(buf)
        assertNotNull(decoded)
        assertEquals(MessageType.PEER_PING, decoded!!.type)
        assertEquals(MeshFrame.BROADCAST, decoded.senderId)
        assertEquals(MeshFrame.BROADCAST, decoded.targetId)
    }

    @Test
    fun decode_wrongMagic1_returnsNull() {
        val buf = ByteArray(MeshFrame.HEADER_SIZE)
        buf[0] = 0x00
        buf[1] = MeshFrame.MAGIC2
        assertNull(MeshFrame.decode(buf))
    }

    @Test
    fun decode_wrongMagic2_returnsNull() {
        val buf = ByteArray(MeshFrame.HEADER_SIZE)
        buf[0] = MeshFrame.MAGIC1
        buf[1] = 0x00
        assertNull(MeshFrame.decode(buf))
    }

    @Test
    fun decode_invalidMessageType_returnsNull() {
        val buf = ByteArray(MeshFrame.HEADER_SIZE)
        buf[0] = MeshFrame.MAGIC1
        buf[1] = MeshFrame.MAGIC2
        buf[2] = MeshFrame.VERSION.toByte()
        buf[3] = 0xFF.toByte() // invalid type
        buf[4] = 1
        buf[5] = 1
        buf[6] = 0
        assertNull(MeshFrame.decode(buf))
    }

    @Test
    fun decode_tooSmallBuffer_returnsNull() {
        assertNull(MeshFrame.decode(ByteArray(MeshFrame.HEADER_SIZE - 1)))
    }

    // =================== msgSeq ===================

    @Test
    fun msgSeq_zero() {
        val frame = MeshFrame(
            type = MessageType.TEXT,
            hopLimit = 1,
            ttl = 1,
            encrypted = false,
            senderId = idA,
            targetId = idB,
            msgSeq = 0,
            payload = ByteArray(0),
            senderPublicKey = null,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(0L, decoded.msgSeq)
    }

    @Test
    fun msgSeq_maxLong() {
        val frame = MeshFrame(
            type = MessageType.TEXT,
            hopLimit = 1,
            ttl = 1,
            encrypted = false,
            senderId = idA,
            targetId = idB,
            msgSeq = Long.MAX_VALUE,
            payload = ByteArray(0),
            senderPublicKey = null,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(Long.MAX_VALUE, decoded.msgSeq)
    }

    @Test
    fun msgSeq_timestamp() {
        val ts = System.currentTimeMillis()
        val frame = MeshFrame(
            type = MessageType.TEXT,
            hopLimit = 1,
            ttl = 1,
            encrypted = false,
            senderId = idA,
            targetId = idB,
            msgSeq = ts,
            payload = ByteArray(0),
            senderPublicKey = null,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(ts, decoded.msgSeq)
    }

    // =================== Payload preservation ===================

    @Test
    fun payload_emptyArray() {
        val frame = MeshFrame(
            type = MessageType.TEXT,
            hopLimit = 1,
            ttl = 1,
            encrypted = false,
            senderId = idA,
            targetId = idB,
            msgSeq = 1,
            payload = ByteArray(0),
            senderPublicKey = null,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun payload_largePayload() {
        val largePayload = ByteArray(4096) { (it % 256).toByte() }
        val frame = MeshFrame(
            type = MessageType.FILE_CHUNK,
            hopLimit = 1,
            ttl = 1,
            encrypted = false,
            senderId = idA,
            targetId = idB,
            msgSeq = 1,
            payload = largePayload,
            senderPublicKey = null,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertArrayEquals(largePayload, decoded.payload)
    }

    @Test
    fun payload_binaryData() {
        val binary = ByteArray(100) { it.toByte() }
        binary[0] = 0x00
        binary[50] = 0xFF.toByte()
        binary[99] = 0x80.toByte()
        val frame = MeshFrame(
            type = MessageType.FILE_CHUNK,
            hopLimit = 1,
            ttl = 1,
            encrypted = false,
            senderId = idA,
            targetId = idB,
            msgSeq = 1,
            payload = binary,
            senderPublicKey = null,
        )
        val decoded = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertArrayEquals(binary, decoded.payload)
    }

    // =================== Data class ===================

    @Test
    fun meshFrame_dataClassEquality() {
        val f1 = MeshFrame(
            type = MessageType.TEXT,
            hopLimit = 2,
            ttl = 6,
            encrypted = true,
            senderId = idA,
            targetId = idB,
            msgSeq = 42,
            payload = "hello".toByteArray(),
            senderPublicKey = null,
        )
        val f2 = MeshFrame(
            type = MessageType.TEXT,
            hopLimit = 2,
            ttl = 6,
            encrypted = true,
            senderId = idA,
            targetId = idB,
            msgSeq = 42,
            payload = "hello".toByteArray(),
            senderPublicKey = null,
        )
        // ByteArray uses reference equality in data class equals, so check fields manually
        assertEquals(f1.type, f2.type)
        assertEquals(f1.hopLimit, f2.hopLimit)
        assertEquals(f1.ttl, f2.ttl)
        assertEquals(f1.encrypted, f2.encrypted)
        assertEquals(f1.senderId, f2.senderId)
        assertEquals(f1.targetId, f2.targetId)
        assertEquals(f1.msgSeq, f2.msgSeq)
        assertTrue(f1.payload.contentEquals(f2.payload))
    }

    @Test
    fun meshFrame_copyModifiesField() {
        val original = MeshFrame(
            type = MessageType.TEXT,
            hopLimit = 4,
            ttl = 6,
            encrypted = true,
            senderId = idA,
            targetId = idB,
            msgSeq = 42,
            payload = "hello".toByteArray(),
            senderPublicKey = null,
        )
        val modified = original.copy(hopLimit = 3)
        assertEquals(3, modified.hopLimit)
        assertEquals(4, original.hopLimit)
    }
}
