package com.meshnet.meshnet_app.tep

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

data class TepEnvelope(
    val protocol: String,
    val version: String,
    val eventId: String,
    val type: String,
    val source: String,
    val timestamp: String,
    val correlationId: String?,
    val idempotencyKey: String?,
    val payload: ByteArray,
) {
    fun dedupeKey(): String = idempotencyKey ?: eventId

    companion object {
        const val PROTOCOL = "tep"
        const val VERSION = "1.0"

        fun build(
            type: String,
            source: String,
            payload: ByteArray,
            correlationId: String? = null,
            idempotencyKey: String? = null,
            eventId: String = UUID.randomUUID().toString(),
        ): TepEnvelope {
            require(type.isNotBlank()) { "type required" }
            require(source.isNotBlank()) { "source required" }

            return TepEnvelope(
                protocol = PROTOCOL,
                version = VERSION,
                eventId = eventId,
                type = type,
                source = source,
                timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(System.currentTimeMillis())),
                correlationId = correlationId,
                idempotencyKey = idempotencyKey,
                payload = payload,
            )
        }
    }
}