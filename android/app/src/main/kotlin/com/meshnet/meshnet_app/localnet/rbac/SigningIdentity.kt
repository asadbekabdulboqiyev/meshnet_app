package com.meshnet.meshnet_app.localnet.rbac

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Long-term ECDSA signing identity for cryptographically proven RBAC.
 *
 * WHY A SEPARATE KEY? The mesh identity key is X25519 (encryption only).
 * Signing uses a dedicated P-256 keypair (separation of duties: signing vs
 * encryption keys must never share a secret). P-256 + SHA256withECDSA is
 * available through stock JCA on every Android release (API 1+) and on the
 * JVM, so no extra dependency and no API-level cliff.
 *
 * Wire/storage encodings are base64 of X.509 (public) and PKCS#8 (private).
 */
object SigningIdentity {

    private const val ALGORITHM = "EC"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val KEY_SIZE = 256

    /** A generated or loaded signing identity. */
    class Identity(
        val keyPair: KeyPair,
    ) {
        /** Base64 X.509 encoded public key — safe to broadcast. */
        fun publicKeyB64(): String =
            b64(keyPair.public.encoded)

        /** Signs arbitrary bytes; returns base64 DER signature. */
        fun sign(data: ByteArray): String =
            b64(Signature.getInstance(SIGNATURE_ALGORITHM).apply {
                initSign(keyPair.private)
                update(data)
            }.sign())
    }

    /** Generate a fresh identity. */
    fun generate(): Identity =
        Identity(KeyPairGenerator.getInstance(ALGORITHM).apply {
            initialize(KEY_SIZE)
        }.generateKeyPair())

    /**
     * Reconstruct an identity from stored encodings.
     * Returns null when inputs are malformed.
     */
    fun fromStored(privateKeyB64: String, publicKeyB64: String): Identity? = try {
        val kf = KeyFactory.getInstance(ALGORITHM)
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(unb64(privateKeyB64)))
        val pub = kf.generatePublic(X509EncodedKeySpec(unb64(publicKeyB64)))
        Identity(KeyPair(pub, priv))
    } catch (_: Exception) {
        null
    }

    /**
     * Verify a signature against a base64 X.509 public key.
     * Never throws — invalid input simply returns false.
     */
    fun verify(publicKeyB64: String, data: ByteArray, signatureB64: String): Boolean = try {
        val kf = KeyFactory.getInstance(ALGORITHM)
        val pub = kf.generatePublic(X509EncodedKeySpec(unb64(publicKeyB64)))
        Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initVerify(pub)
            update(data)
        }.verify(unb64(signatureB64))
    } catch (_: Exception) {
        false
    }

    // --- encoding helpers ---

    private fun b64(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    private fun unb64(data: String): ByteArray =
        Base64.getDecoder().decode(data)
}
