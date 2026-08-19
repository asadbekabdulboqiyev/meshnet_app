package com.meshnet.meshnet_app.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * MeshFrame - wire (on-the-air) message format.
 * Layout (MESH_PROTOCOL.md):
 *
 * | ofset | maydon           | hajm |
 * |-------|------------------|------|
 * | 0-1   | magic 0x4D 0x4E  | 2    |
 * | 2     | version 0x01     | 1    |
 * | 3     | type             | 1    |
 * | 4     | hop_limit        | 1    |
 * | 5     | ttl              | 1    |
 * | 6     | flags            | 1    |
 * | 7-22  | sender_id        | 16   |
 * | 23-38 | target_id        | 16   |
 * | 39-46 | msg_seq          | 8    |
 * | 47-?  | payload          | n    |
 *
 * Broadcast target: 16 null bytes.
 */
data class MeshFrame(
    val type: MessageType,
    val hopLimit: Int,
    val ttl: Int,
    val encrypted: Boolean,
    val senderId: String,
    val targetId: String,   // "broadcast" [---] if to all
    val msgSeq: Long,
    val payload: ByteArray,
    val senderPublicKey: ByteArray?, // only in PAIR_REQ/PAIR_ACK
) {

    companion object {
        const val MAGIC1 = 0x4D.toByte()
        const val MAGIC2 = 0x4E.toByte()
        const val VERSION = 0x01
        const val BROADCAST = "broadcast"
        const val HEADER_SIZE = 47
        const val ID_BYTES = 16

        fun encode(frame: MeshFrame): ByteArray {
            val idBytes = 16
            val pubKeyLen = frame.senderPublicKey?.size ?: 0
            val buf = ByteBuffer.allocate(HEADER_SIZE + pubKeyLen + frame.payload.size)
                .order(ByteOrder.BIG_ENDIAN)

            buf.put(MAGIC1)
            buf.put(MAGIC2)
            buf.put(VERSION.toByte())
            buf.put(frame.type.code)
            buf.put(frame.hopLimit.toByte())
            buf.put(frame.ttl.toByte())
            buf.put((if (frame.encrypted) 0x01 else 0x00).toByte())
            buf.put(encodeId(frame.senderId, idBytes))
            buf.put(encodeId(frame.targetId, idBytes))
            buf.putLong(frame.msgSeq)
            if (pubKeyLen > 0) buf.put(frame.senderPublicKey!!)
            buf.put(frame.payload)
            return buf.array()
        }

        fun decode(bytes: ByteArray): MeshFrame? {
            if (bytes.size < HEADER_SIZE) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            val m1 = buf.get(); val m2 = buf.get()
            if (m1 != MAGIC1 || m2 != MAGIC2) return null
            buf.get() // version
            val type = MessageType.fromCode(buf.get()) ?: return null
            val hopLimit = buf.get().toInt()
            val ttl = buf.get().toInt()
            val flags = buf.get()
            val encrypted = (flags.toInt() and 0x01) != 0
            val sender = decodeId(buf, ID_BYTES)
            val target = decodeId(buf, ID_BYTES)
            val seq = buf.long

            // senderPublicKey is present in PAIR_REQ/ACK and FIND_PEER_ACK (32 bytes),
            // if available
            var publicKey: ByteArray? = null
            if (type == MessageType.PAIR_REQ || type == MessageType.PAIR_ACK ||
                type == MessageType.FIND_PEER_ACK
            ) {
                if (buf.remaining() >= 32) {
                    val pk = ByteArray(32)
                    buf.get(pk)
                    publicKey = pk
                }
            }

            val payload = ByteArray(buf.remaining())
            buf.get(payload)

            return MeshFrame(
                type = type,
                hopLimit = hopLimit,
                ttl = ttl,
                encrypted = encrypted,
                senderId = sender,
                targetId = target,
                msgSeq = seq,
                payload = payload,
                senderPublicKey = publicKey,
            )
        }

        private fun encodeId(id: String, len: Int): ByteArray {
            if (id == BROADCAST) return ByteArray(len)
            val bytes = ByteArray(len)
            val uuid = uuidToBytes(id)
            System.arraycopy(uuid, 0, bytes, 0, minOf(uuid.size, len))
            return bytes
        }

        private fun decodeId(buf: ByteBuffer, len: Int): String {
            val raw = ByteArray(len)
            buf.get(raw)
            // broadcast check
            if (raw.all { it == 0.toByte() }) return BROADCAST
            // 16 bytes -> UUID 36-character string
            return bytesToUuid(raw) ?: "unknown"
        }

        private fun uuidToBytes(uuid: String): ByteArray {
            return try {
                val u = UUID.fromString(uuid)
                val bb = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                bb.putLong(u.mostSignificantBits)
                bb.putLong(u.leastSignificantBits)
                bb.array()
            } catch (e: Exception) {
                ByteArray(16)
            }
        }

        private fun bytesToUuid(raw: ByteArray): String? {
            return try {
                val bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
                UUID(bb.long, bb.long).toString()
            } catch (e: Exception) {
                null
            }
        }
    }
}