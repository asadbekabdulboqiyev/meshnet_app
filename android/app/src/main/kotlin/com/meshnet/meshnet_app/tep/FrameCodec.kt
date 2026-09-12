package com.meshnet.meshnet_app.tep

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

object FrameCodec {
    private const val FRAME_VERSION: Byte = 0x01
    private const val HEADER_LEN_BYTES = 2
    private const val PAYLOAD_LEN_BYTES = 4
    private const val UUID_BYTES = 16
    private const val TS_BYTES = 8
    private const val FLAGS_BYTES = 1
    private const val FIXED_HEADER = 1 + HEADER_LEN_BYTES + PAYLOAD_LEN_BYTES + UUID_BYTES + TS_BYTES + FLAGS_BYTES
    private const val SIG_BYTES = 32

    private const val FLAG_CORRELATION = 0x01
    private const val FLAG_IDEMPOTENCY = 0x02

    fun encode(envelope: TepEnvelope, secret: ByteArray): ByteArray {
        val signature = Signature.rawHmac(envelope, envelope.payload, secret)
        require(signature.size == SIG_BYTES)

        val header = ByteArrayOutputStream()
        writeVarString(header, envelope.source)
        writeVarString(header, envelope.type)
        envelope.correlationId?.let { writeVarString(header, it) }
        envelope.idempotencyKey?.let { writeVarString(header, it) }

        val out = ByteBuffer.allocate(FIXED_HEADER + header.size() + envelope.payload.size + SIG_BYTES)
        out.put(FRAME_VERSION)
        out.putShort(header.size().toShort())
        out.putInt(envelope.payload.size)
        out.put(uuidBytes(envelope.eventId))
        out.putLong(Instant.parse(envelope.timestamp).toEpochMilli())
        var flags = 0
        if (envelope.correlationId != null) flags = flags or FLAG_CORRELATION
        if (envelope.idempotencyKey != null) flags = flags or FLAG_IDEMPOTENCY
        out.put(flags.toByte())
        out.put(header.toByteArray())
        out.put(envelope.payload)
        out.put(signature)
        return out.array()
    }

    data class Decoded(val envelope: TepEnvelope, val signature: ByteArray)

    fun decode(frame: ByteArray): Decoded {
        require(frame.size >= FIXED_HEADER) { "frame too short" }
        val buffer = ByteBuffer.wrap(frame)
        val version = buffer.get()
        require(version == FRAME_VERSION) { "unsupported frame version $version" }
        val headerLen = buffer.short.toInt() and 0xFFFF
        val payloadLen = buffer.int
        require(frame.size == FIXED_HEADER + headerLen + payloadLen + SIG_BYTES) { "frame length mismatch" }

        val eventIdBytes = ByteArray(UUID_BYTES)
        buffer.get(eventIdBytes)
        val eventId = uuidFromBytes(eventIdBytes)
        val unixMs = buffer.long
        val flags = buffer.get().toInt() and 0xFF

        val header = ByteArray(headerLen)
        buffer.get(header)
        val headerIn = DataInputStream(ByteArrayInputStream(header))
        val source = readVarString(headerIn)
        val type = readVarString(headerIn)
        val correlationId = if (flags and FLAG_CORRELATION != 0) readVarString(headerIn) else null
        val idempotencyKey = if (flags and FLAG_IDEMPOTENCY != 0) readVarString(headerIn) else null

        val payload = ByteArray(payloadLen)
        buffer.get(payload)
        val signature = ByteArray(SIG_BYTES)
        buffer.get(signature)

        val envelope = TepEnvelope(
            protocol = TepEnvelope.PROTOCOL,
            version = TepEnvelope.VERSION,
            eventId = eventId,
            type = type,
            source = source,
            timestamp = Instant.ofEpochMilli(unixMs).toString(),
            correlationId = correlationId,
            idempotencyKey = idempotencyKey,
            payload = payload,
        )
        return Decoded(envelope, signature)
    }

    fun verify(frame: ByteArray, secret: ByteArray): Boolean {
        val decoded = decode(frame)
        val expected = Signature.rawHmac(decoded.envelope, decoded.envelope.payload, secret)
        return MessageDigestIsEqual(expected, decoded.signature)
    }

    private fun writeVarString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= 255) { "varstring too long (>255 bytes)" }
        out.write(bytes.size)
        out.write(bytes)
    }

    private fun readVarString(input: DataInputStream): String {
        val len = input.readUnsignedByte()
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun uuidBytes(uuid: String): ByteArray {
        val u = UUID.fromString(uuid)
        return ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
    }

    private fun uuidFromBytes(bytes: ByteArray): String {
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long).toString()
    }

    private fun MessageDigestIsEqual(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}