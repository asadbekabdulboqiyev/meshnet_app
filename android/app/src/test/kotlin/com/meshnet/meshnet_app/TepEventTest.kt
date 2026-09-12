package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.protocol.MeshFrame
import com.meshnet.meshnet_app.protocol.MessageType
import com.meshnet.meshnet_app.tep.FrameCodec
import com.meshnet.meshnet_app.tep.Signature
import com.meshnet.meshnet_app.tep.TepEnvelope
import com.meshnet.meshnet_app.tep.TepEventManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * TEP (Teno Event Protocol) mesh integrasiya testlari.
 * Spec: spec/transport-mesh.md — MessageType TEP_EVENT (41/0x29) broadcast relay.
 */
class TepEventTest {

    private val idA = "11111111-1111-1111-1111-111111111111"
    private val idB = "22222222-2222-2222-2222-222222222222"
    private val secret = ByteArray(32) { it.toByte() }

    private fun manager(deviceId: String) =
        TepEventManager(deviceId, "meshnet", secret)

    // =================== MessageType ===================

    @Test
    fun tepEvent_codeIsDecimal41Hex0x29() {
        assertEquals(41, MessageType.TEP_EVENT.code.toInt() and 0xFF)
        assertEquals(0x29.toByte(), MessageType.TEP_EVENT.code)
    }

    @Test
    fun tepEvent_fromCode_roundtrip() {
        assertEquals(MessageType.TEP_EVENT, MessageType.fromCode(0x29))
    }

    @Test
    fun allCodes_stillUniqueWithTepEvent() {
        val codes = MessageType.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    // =================== FrameCodec roundtrip ===================

    @Test
    fun frameCodec_encodeVerifyDecode() {
        val envelope = TepEnvelope.build(
            type = TepEventManager.TYPE_PEER_JOINED,
            source = "meshnet",
            payload = "peerId=$idB".toByteArray(),
        )
        val frame = FrameCodec.encode(envelope, secret)
        assertTrue(FrameCodec.verify(frame, secret))
        val decoded = FrameCodec.decode(frame)
        assertEquals(TepEventManager.TYPE_PEER_JOINED, decoded.envelope.type)
        assertEquals("meshnet", decoded.envelope.source)
        assertEquals(envelope.eventId, decoded.envelope.eventId)
        assertArrayEquals(envelope.payload, decoded.envelope.payload)
    }

    @Test
    fun frameCodec_verify_withWrongSecret_fails() {
        val envelope = TepEnvelope.build(
            type = "peer.joined", source = "meshnet", payload = ByteArray(0),
        )
        val frame = FrameCodec.encode(envelope, secret)
        val wrongSecret = ByteArray(32) { (it.toByte() + 1).toByte() }
        assertFalse(FrameCodec.verify(frame, wrongSecret))
    }

    @Test
    fun signature_canonical_form() {
        val envelope = TepEnvelope.build(
            type = "file.transferred", source = "meshnet", payload = "f".toByteArray(),
        )
        val canonical = Signature.canonical(envelope, envelope.payload)
        val text = String(canonical)
        assertTrue(text.startsWith("tep\n1.0\n"))
        assertTrue(text.contains(envelope.eventId))
        assertTrue(text.contains("file.transferred"))
        assertTrue(text.endsWith("f"))
    }

    // =================== TepEventManager: build frame ===================

    @Test
    fun buildTepFrame_isBroadcastTepEvent() {
        val m = manager(idA)
        val frame = m.buildTepFrame(
            type = TepEventManager.TYPE_PEER_JOINED,
            payload = "peerId=$idB".toByteArray(),
            msgSeq = 7,
        )
        assertEquals(MessageType.TEP_EVENT, frame.type)
        assertEquals(MeshFrame.BROADCAST, frame.targetId)
        assertEquals(idA, frame.senderId)
        assertEquals(6, frame.hopLimit)
        assertEquals(6, frame.ttl)
        // mesh frame header roundtrip
        val decodedFrame = MeshFrame.decode(MeshFrame.encode(frame))
        assertNotNull(decodedFrame)
        assertEquals(MessageType.TEP_EVENT, decodedFrame!!.type)
    }

    @Test
    fun buildTepFrame_payloadVerifiesWithManagerSecret() {
        val m = manager(idA)
        val payload = "peerId=$idB".toByteArray()
        val frame = m.buildTepFrame(
            type = TepEventManager.TYPE_MESSAGE_RELAYED,
            payload = payload,
            msgSeq = 1,
        )
        assertTrue(FrameCodec.verify(frame.payload, secret))
    }

    // =================== TepEventManager: receive ===================

    @Test
    fun decodeReceived_emitsVerifiedEvent() {
        val sender = manager(idA)
        val receiver = manager(idB)
        val epoch = AtomicInteger(0)

        val seen = mutableListOf<TepEventManager.ReceivedEvent>()
        receiver.listener = object : TepEventManager.Listener {
            override fun onTepEvent(event: TepEventManager.ReceivedEvent) {
                seen.add(event)
            }
        }

        val frame = sender.buildTepFrame(
            type = TepEventManager.TYPE_FILE_TRANSFERRED,
            payload = "file=notes.txt;bytes=1337".toByteArray(),
            msgSeq = epoch.incrementAndGet().toLong(),
        )
        val event = receiver.decodeReceived(frame.payload)

        assertNotNull(event)
        assertEquals(TepEventManager.TYPE_FILE_TRANSFERRED, event!!.type)
        assertEquals("meshnet", event.senderSource)
        assertEquals(1, seen.size)
    }

    @Test
    fun decodeReceived_deduplicatesByEventId() {
        val sender = manager(idA)
        val receiver = manager(idB)
        val frame = sender.buildTepFrame(
            type = TepEventManager.TYPE_PEER_JOINED,
            payload = "peerId=$idB".toByteArray(),
            msgSeq = 5,
        )
        assertNotNull(receiver.decodeReceived(frame.payload))
        assertNull(receiver.decodeReceived(frame.payload))
        // mesh frame roundtrip orqali yuborilgan takroriy TEP (bir xil eventId) ham dedupe
        val transported = MeshFrame.decode(MeshFrame.encode(frame))!!
        assertArrayEquals(frame.payload, transported.payload)
        assertNull(receiver.decodeReceived(transported.payload))
    }

    @Test
    fun decodeReceived_rejectsBadSignature() {
        val sender = manager(idA)
        val receiver = TepEventManager(idB, "meshnet", ByteArray(32) { (it.toByte() + 5).toByte() })
        val frame = sender.buildTepFrame(
            type = TepEventManager.TYPE_GROUP_UPDATED,
            payload = "g=1".toByteArray(),
            msgSeq = 3,
        )
        assertNull(receiver.decodeReceived(frame.payload))
    }

    @Test
    fun isFirstSeen_secondCallReturnsFalse() {
        val m = manager(idA)
        assertTrue(m.isFirstSeen("event-1"))
        assertFalse(m.isFirstSeen("event-1"))
        assertTrue(m.isFirstSeen("event-2"))
        assertEquals(2, m.seenCount())
    }
}