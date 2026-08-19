package com.meshnet.meshnet_app.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.security.Security
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MeshNet E2E cryptography.
 *
 * Key exchange: X25519 (RFC 7748) - BouncyCastle
 * Confidentiality/integrity: ChaCha20-Poly1305 (AEAD) - Android javax.crypto
 *
 * No keys are "invented from scratch" — standard, well-tested
 * constructions are used.
 */
object MeshCrypto {

    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val CHACHA_CIPHER = "ChaCha20-Poly1305"

    private val secureRandom = SecureRandom()

    /**
     * ChaCha20-Poly1305 cipher object.
     *
     * IMPORTANT: Forcing the "BC" provider name is WRONG — on Android
     * `Security.getProvider("BC")` returns the device's built-in OLD
     * BouncyCastle which does NOT have ChaCha20-Poly1305
     * (NoSuchAlgorithmException is thrown). On Android 9+ (API 28) the default
     * provider (Conscrypt) fully supports this — use that first,
     * fall back to the app's BC provider for API 26-27.
     */
    private fun chachaCipher(): Cipher {
        try {
            return Cipher.getInstance(CHACHA_CIPHER)
        } catch (_: GeneralSecurityException) {
            // API 26-27 fallback: app's BouncyCastle provider
            return try {
                if (Security.getProvider("BC") == null) {
                    Security.addProvider(BouncyCastleProvider())
                }
                Cipher.getInstance(CHACHA_CIPHER, "BC")
            } catch (e: GeneralSecurityException) {
                throw IllegalStateException("ChaCha20-Poly1305 not available", e)
            }
        }
    }

        fun chachaCipherWithKey(key: ByteArray): javax.crypto.Cipher {
            return try {
                Cipher.getInstance(CHACHA_CIPHER)
            } catch (_: java.security.GeneralSecurityException) {
                if (Security.getProvider("BC") == null) {
                    Security.addProvider(BouncyCastleProvider())
                }
                Cipher.getInstance(CHACHA_CIPHER, "BC")
            }
        }

    /** X25519 keypair. privateKey is secret, publicKey is public. */
    data class KeyPair(
        val privateKey: ByteArray,
        val publicKey: ByteArray,
    )

    /** Generates a new X25519 keypair. */
    fun generateKeyPair(): KeyPair {
        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(secureRandom))
        val pair = generator.generateKeyPair()
        val priv = (pair.private as X25519PrivateKeyParameters)
        val pub = (pair.public as X25519PublicKeyParameters)
        return KeyPair(
            privateKey = priv.encoded,
            publicKey = pub.encoded,
        )
    }

    /**
     * Shared secret (session key). Computed once per (me, peer) pair.
     * Diffie-Hellman: X25519(myPrivate, peerPublic).
     */
    fun computeSharedSecret(myPrivate: ByteArray, peerPublic: ByteArray): ByteArray {
        val private = X25519PrivateKeyParameters(myPrivate, 0)
        val public = X25519PublicKeyParameters(peerPublic, 0)
        val agreement = X25519Agreement()
        agreement.init(private)
        val secret = ByteArray(KEY_BYTES)
        agreement.calculateAgreement(public, secret, 0)
        return secret
    }

    /**
     * ChaCha20-Poly1305 (AEAD) encryption.
     * Format: nonce(12) || ciphertext (with authentication tag).
     */
    fun encrypt(sharedSecret: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val cipher = chachaCipher()
        val key = SecretKeySpec(sharedSecret, "ChaCha20")
        val nonce = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return nonce + ciphertext
    }

    /** Decrypt encrypt()'s output. Throws exception if AEAD tag is invalid. */
    fun decrypt(sharedSecret: ByteArray, payloadWithNonce: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(payloadWithNonce.size >= NONCE_BYTES) { "Payload shorter than nonce" }
        val nonce = payloadWithNonce.copyOfRange(0, NONCE_BYTES)
        val ciphertext = payloadWithNonce.copyOfRange(NONCE_BYTES, payloadWithNonce.size)
        val cipher = chachaCipher()
        val key = SecretKeySpec(sharedSecret, "ChaCha20")
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    /**
     * URL-safe base64. Works in JVM unit tests too, convenient for QR codes
     * (no line breaks, `-`/`_` instead of `+`/`/`).
     */
    fun b64(data: ByteArray): String = Base64.getUrlEncoder().encodeToString(data)
    fun unb64(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
}