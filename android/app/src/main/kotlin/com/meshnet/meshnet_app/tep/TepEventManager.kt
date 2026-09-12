package com.meshnet.meshnet_app.tep

import com.meshnet.meshnet_app.protocol.MessageType
import com.meshnet.meshnet_app.protocol.MeshFrame
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * TepEventManager — mesh ichida app-level TEP eventlar:
 * peer.joined, message.relayed, file.transferred, group.updated.
 *
 * Frame'larni RoutingEngine (MessageType.TEP_EVENT) orqali relay qilinadi,
 * hopLimit/ttl o'zgartirilmaydi (spec/transport-mesh.md). Idempotency
 * event_id -> seen (TTL 24 soat) bilan ta'minlanadi.
 */
class TepEventManager(
    private val senderDeviceId: String,
    private val source: String,
    private val secret: ByteArray,
) {
    data class ReceivedEvent(
        val type: String,
        val eventId: String,
        val payload: ByteArray,
        val senderSource: String,
        val timestamp: String,
    )

    interface Listener {
        fun onTepEvent(event: ReceivedEvent)
    }

    @Volatile
    var listener: Listener? = null

    private val seenEvents = ConcurrentHashMap<String, Long>()

    /** Encode + sign TEP event into a mesh frame payload. */
    fun buildEventBytes(
        type: String,
        payload: ByteArray,
        correlationId: String? = null,
        idempotencyKey: String? = null,
    ): ByteArray {
        val envelope = TepEnvelope.build(
            type = type,
            source = source,
            payload = payload,
            correlationId = correlationId,
            idempotencyKey = idempotencyKey ?: type,
        )
        return FrameCodec.encode(envelope, secret)
    }

    /** Build a signed MeshFrame (broadcast) carrying a TEP event. */
    fun buildTepFrame(
        type: String,
        payload: ByteArray,
        msgSeq: Long,
        correlationId: String? = null,
        idempotencyKey: String? = null,
    ): MeshFrame {
        return MeshFrame(
            type = MessageType.TEP_EVENT,
            hopLimit = MAX_HOP,
            ttl = MAX_TTL,
            encrypted = false, // TEP asosiy sifrlash oz ustida; payload E2E boshqa qatlamda
            senderId = senderDeviceId,
            targetId = MeshFrame.BROADCAST,
            msgSeq = msgSeq,
            payload = buildEventBytes(type, payload, correlationId, idempotencyKey),
            senderPublicKey = null,
        )
    }

    /** Verify + dedupe a received TEP frame payload from the mesh. */
    fun decodeReceived(frameBytes: ByteArray): ReceivedEvent? {
        if (!FrameCodec.verify(frameBytes, secret)) return null
        val decoded = FrameCodec.decode(frameBytes)
        val envelope = decoded.envelope
        if (!isFirstSeen(envelope.eventId)) return null
        val event = ReceivedEvent(
            type = envelope.type,
            eventId = envelope.eventId,
            payload = envelope.payload,
            senderSource = envelope.source,
            timestamp = envelope.timestamp,
        )
        listener?.onTepEvent(event)
        return event
    }

    /** Idempotency: event_id 24 soat TTL (loop prevention). */
    fun isFirstSeen(eventId: String): Boolean {
        val now = System.currentTimeMillis()
        // TTL cleanup
        if (seenEvents.size > 10_000) {
            val cutoff = now - TTL_MS
            seenEvents.entries.removeIf { it.value < cutoff }
        }
        if (seenEvents.putIfAbsent(eventId, now) != null) return false
        return true
    }

    fun seenCount(): Int = seenEvents.size

    companion object {
        const val TTL_MS = 24 * 60 * 60 * 1000L // 24 soat

        const val MAX_HOP = 6
        const val MAX_TTL = 6

        // MeshNet app-level TEP event types (spec/transport-mesh.md)
        const val TYPE_PEER_JOINED = "peer.joined"
        const val TYPE_MESSAGE_RELAYED = "message.relayed"
        const val TYPE_FILE_TRANSFERRED = "file.transferred"
        const val TYPE_GROUP_UPDATED = "group.updated"
    }
}