package com.meshnet.meshnet_app.tep

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Signature {
    const val SIG_PREFIX = "v1."

    fun canonical(envelope: TepEnvelope, rawPayload: ByteArray): ByteArray {
        val joined = listOf(
            "tep",
            TepEnvelope.VERSION,
            envelope.eventId,
            envelope.timestamp,
            envelope.source,
            envelope.type,
            String(rawPayload, StandardCharsets.UTF_8),
        ).joinToString("\n")
        return joined.toByteArray(StandardCharsets.UTF_8)
    }

    fun sign(envelope: TepEnvelope, rawPayload: ByteArray, secret: ByteArray): String {
        return SIG_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac(envelope, rawPayload, secret))
    }

    fun verify(envelope: TepEnvelope, rawPayload: ByteArray, signature: String, secret: ByteArray): Boolean {
        if (!signature.startsWith(SIG_PREFIX)) return false
        val provided = try {
            Base64.getUrlDecoder().decode(signature.removePrefix(SIG_PREFIX))
        } catch (e: IllegalArgumentException) {
            return false
        }
        val expected = rawHmac(envelope, rawPayload, secret)
        return MessageDigest.isEqual(provided, expected)
    }

    fun rawHmac(envelope: TepEnvelope, rawPayload: ByteArray, secret: ByteArray): ByteArray {
        require(secret.size >= 32) { "secret must be at least 32 bytes" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(canonical(envelope, rawPayload))
    }
}