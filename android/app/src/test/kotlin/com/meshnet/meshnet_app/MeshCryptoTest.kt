package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.crypto.MeshCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** X25519 + ChaCha20-Poly1305 E2E kriptografiyasi testlari. */
class MeshCryptoTest {

    @Test
    fun generateKeyPair_returns32ByteKeys() {
        val pair = MeshCrypto.generateKeyPair()
        assertEquals(32, pair.privateKey.size)
        assertEquals(32, pair.publicKey.size)
    }

    @Test
    fun keyPairs_areUnique() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        assertFalse(a.publicKey.contentEquals(b.publicKey))
    }

    @Test
    fun sharedSecret_isSymmetric() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()

        val sa = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        val sb = MeshCrypto.computeSharedSecret(b.privateKey, a.publicKey)

        assertArrayEquals(sa, sb)
    }

    @Test
    fun sharedSecret_differsBetweenPeers() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val c = MeshCrypto.generateKeyPair()

        val sab = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        val sac = MeshCrypto.computeSharedSecret(a.privateKey, c.publicKey)

        assertFalse(sab.contentEquals(sac))
    }

    @Test
    fun encryptDecrypt_roundtrip() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val secret = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        val plain = "maxfiy xabar".toByteArray(Charsets.UTF_8)

        val ciphertext = MeshCrypto.encrypt(secret, plain)
        assertFalse(plain.contentEquals(ciphertext))

        val decrypted = MeshCrypto.decrypt(secret, ciphertext)
        assertArrayEquals(plain, decrypted)
        assertEquals("maxfiy xabar", String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun encrypt_usesUniqueNonce() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val secret = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        val plain = "salom".toByteArray(Charsets.UTF_8)

        val c1 = MeshCrypto.encrypt(secret, plain)
        val c2 = MeshCrypto.encrypt(secret, plain)
        assertFalse(c1.contentEquals(c2)) // nonce har safar boshqacha
    }

    @Test
    fun decrypt_wrongSecret_fails() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val c = MeshCrypto.generateKeyPair()
        val secretAB = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        val secretAC = MeshCrypto.computeSharedSecret(a.privateKey, c.publicKey)

        val ciphertext = MeshCrypto.encrypt(secretAB, "salom".toByteArray(Charsets.UTF_8))

        assertThrows(Exception::class.java) {
            MeshCrypto.decrypt(secretAC, ciphertext)
        }
    }

    @Test
    fun decrypt_tamperedCiphertext_fails() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val secret = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)

        val ciphertext = MeshCrypto.encrypt(secret, "salom".toByteArray(Charsets.UTF_8))
        ciphertext[ciphertext.size - 1] = (ciphertext.last().toInt() xor 0x01).toByte()

        assertThrows(Exception::class.java) {
            MeshCrypto.decrypt(secret, ciphertext)
        }
    }

    @Test
    fun aad_protectsDecryption() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val secret = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        val aad = "MeshNet:22222222-2222-2222-2222-222222222222".toByteArray(Charsets.UTF_8)

        val ciphertext = MeshCrypto.encrypt(secret, "salom".toByteArray(Charsets.UTF_8), aad)

        // Boshqa AAD bilan ochib bo'lmaydi
        val wrongAad = "MeshNet:Evil".toByteArray(Charsets.UTF_8)
        assertThrows(Exception::class.java) {
            MeshCrypto.decrypt(secret, ciphertext, wrongAad)
        }

        // To'g'ri AAD bilan ochiladi
        val decrypted = MeshCrypto.decrypt(secret, ciphertext, aad)
        assertArrayEquals("salom".toByteArray(Charsets.UTF_8), decrypted)
    }

    @Test
    fun b64_roundtrip() {
        val raw = ByteArray(32) { it.toByte() }
        val encoded = MeshCrypto.b64(raw)
        assertArrayEquals(raw, MeshCrypto.unb64(encoded))
    }
}
