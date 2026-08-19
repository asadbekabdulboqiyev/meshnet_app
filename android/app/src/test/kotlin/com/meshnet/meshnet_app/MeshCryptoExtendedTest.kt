package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.crypto.MeshCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MeshCrypto kengaytirilgan testlari: edge cases, empty input, large input,
 * b64 edge cases, multiple operations, key properties.
 */
class MeshCryptoExtendedTest {

    // =================== generateKeyPair ===================

    @Test
    fun generateKeyPair_uniqueKeyPairsEachTime() {
        val pairs = (1..10).map { MeshCrypto.generateKeyPair() }
        val publicKeys = pairs.map { it.publicKey }
        assertEquals(10, publicKeys.toSet().size)
    }

    @Test
    fun generateKeyPair_privateKeyIs32Bytes() {
        val kp = MeshCrypto.generateKeyPair()
        assertEquals(32, kp.privateKey.size)
    }

    @Test
    fun generateKeyPair_publicKeyIs32Bytes() {
        val kp = MeshCrypto.generateKeyPair()
        assertEquals(32, kp.publicKey.size)
    }

    // =================== computeSharedSecret ===================

    @Test
    fun computeSharedSecret_returns32Bytes() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val secret = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        assertEquals(32, secret.size)
    }

    @Test
    fun computeSharedSecret_symmetric() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val s1 = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        val s2 = MeshCrypto.computeSharedSecret(b.privateKey, a.publicKey)
        assertArrayEquals(s1, s2)
    }

    @Test
    fun computeSharedSecret_differentPeersGiveDifferentSecrets() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val c = MeshCrypto.generateKeyPair()
        val s1 = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        val s2 = MeshCrypto.computeSharedSecret(a.privateKey, c.publicKey)
        assertFalse(s1.contentEquals(s2))
    }

    @Test
    fun computeSharedSecret_deterministic() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val s1 = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        val s2 = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        assertArrayEquals(s1, s2)
    }

    // =================== encrypt / decrypt ===================

    @Test
    fun encryptDecrypt_emptyPayload() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val secret = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        val plain = ByteArray(0)
        val ct = MeshCrypto.encrypt(secret, plain)
        val dt = MeshCrypto.decrypt(secret, ct)
        assertArrayEquals(plain, dt)
    }

    @Test
    fun encryptDecrypt_singleByte() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val secret = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        val plain = byteArrayOf(0x42)
        val ct = MeshCrypto.encrypt(secret, plain)
        val dt = MeshCrypto.decrypt(secret, ct)
        assertArrayEquals(plain, dt)
    }

    @Test
    fun encryptDecrypt_largePayload() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val secret = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        val plain = ByteArray(10000) { (it % 256).toByte() }
        val ct = MeshCrypto.encrypt(secret, plain)
        val dt = MeshCrypto.decrypt(secret, ct)
        assertArrayEquals(plain, dt)
    }

    @Test
    fun encryptDecrypt_binaryData() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val secret = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        val plain = ByteArray(256) { it.toByte() }
        plain[0] = 0x00
        plain[128] = 0xFF.toByte()
        plain[255] = 0x80.toByte()
        val ct = MeshCrypto.encrypt(secret, plain)
        val dt = MeshCrypto.decrypt(secret, ct)
        assertArrayEquals(plain, dt)
    }

    @Test
    fun encryptDecrypt_unicodeText() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val secret = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        val text = "Salom dunyo! Yangi yil muborak"
        val plain = text.toByteArray(Charsets.UTF_8)
        val ct = MeshCrypto.encrypt(secret, plain)
        val dt = MeshCrypto.decrypt(secret, ct)
        assertEquals(text, String(dt, Charsets.UTF_8))
    }

    @Test
    fun encrypt_ciphertextLongerThanPlaintext() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val secret = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        val plain = "short".toByteArray()
        val ct = MeshCrypto.encrypt(secret, plain)
        assertTrue(ct.size > plain.size)
    }

    @Test
    fun encrypt_ciphertextDiffersFromPlaintext() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val secret = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        val plain = "hello".toByteArray()
        val ct = MeshCrypto.encrypt(secret, plain)
        assertFalse(plain.contentEquals(ct))
    }

    // =================== AAD ===================

    @Test
    fun encryptDecrypt_withAAD() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val secret = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        val aad = "MeshNet:target-id".toByteArray()
        val plain = "secure message".toByteArray()
        val ct = MeshCrypto.encrypt(secret, plain, aad)
        val dt = MeshCrypto.decrypt(secret, ct, aad)
        assertArrayEquals(plain, dt)
    }

    @Test
    fun decrypt_wrongAAD_fails() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val secret = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        val aad1 = "MeshNet:correct".toByteArray()
        val aad2 = "MeshNet:wrong".toByteArray()
        val ct = MeshCrypto.encrypt(secret, "msg".toByteArray(), aad1)
        assertThrows(Exception::class.java) {
            MeshCrypto.decrypt(secret, ct, aad2)
        }
    }

    @Test
    fun encryptDecrypt_withoutAAD_withAADFails() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val secret = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        val ct = MeshCrypto.encrypt(secret, "msg".toByteArray())
        // Trying to decrypt with AAD that wasn't used during encryption
        assertThrows(Exception::class.java) {
            MeshCrypto.decrypt(secret, ct, "some-aad".toByteArray())
        }
    }

    // =================== Tamper detection ===================

    @Test
    fun decrypt_tamperedNonce_fails() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val secret = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        val ct = MeshCrypto.encrypt(secret, "msg".toByteArray())
        // Tamper with nonce (first 12 bytes of ciphertext)
        ct[0] = (ct[0].toInt() xor 0xFF).toByte()
        assertThrows(Exception::class.java) {
            MeshCrypto.decrypt(secret, ct)
        }
    }

    @Test
    fun decrypt_tamperedCiphertext_fails() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val secret = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        val ct = MeshCrypto.encrypt(secret, "msg".toByteArray())
        ct[ct.size - 1] = (ct.last().toInt() xor 0x01).toByte()
        assertThrows(Exception::class.java) {
            MeshCrypto.decrypt(secret, ct)
        }
    }

    @Test
    fun decrypt_truncatedCiphertext_fails() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val secret = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        val ct = MeshCrypto.encrypt(secret, "msg".toByteArray())
        val truncated = ct.copyOf(ct.size - 5)
        assertThrows(Exception::class.java) {
            MeshCrypto.decrypt(secret, truncated)
        }
    }

    // =================== b64 ===================

    @Test
    fun b64_emptyArray() {
        val encoded = MeshCrypto.b64(ByteArray(0))
        assertArrayEquals(ByteArray(0), MeshCrypto.unb64(encoded))
    }

    @Test
    fun b64_singleByte() {
        val raw = byteArrayOf(0x42)
        val encoded = MeshCrypto.b64(raw)
        assertArrayEquals(raw, MeshCrypto.unb64(encoded))
    }

    @Test
    fun b64_32Bytes() {
        val raw = ByteArray(32) { it.toByte() }
        val encoded = MeshCrypto.b64(raw)
        assertArrayEquals(raw, MeshCrypto.unb64(encoded))
    }

    @Test
    fun b64_allZeros() {
        val raw = ByteArray(32) { 0x00 }
        val encoded = MeshCrypto.b64(raw)
        assertArrayEquals(raw, MeshCrypto.unb64(encoded))
    }

    @Test
    fun b64_allOnes() {
        val raw = ByteArray(32) { 0xFF.toByte() }
        val encoded = MeshCrypto.b64(raw)
        assertArrayEquals(raw, MeshCrypto.unb64(encoded))
    }

    @Test
    fun b64_outputIsNonEmpty() {
        val encoded = MeshCrypto.b64(byteArrayOf(1, 2, 3))
        assertTrue(encoded.isNotEmpty())
    }

    @Test
    fun b64_deterministic() {
        val raw = byteArrayOf(1, 2, 3, 4, 5)
        val e1 = MeshCrypto.b64(raw)
        val e2 = MeshCrypto.b64(raw)
        assertEquals(e1, e2)
    }

    @Test
    fun b64_differentInputsDifferentOutputs() {
        val e1 = MeshCrypto.b64(byteArrayOf(1))
        val e2 = MeshCrypto.b64(byteArrayOf(2))
        assertFalse(e1 == e2)
    }

    @Test
    fun b64_largeInput() {
        val raw = ByteArray(10000) { it.toByte() }
        val encoded = MeshCrypto.b64(raw)
        assertArrayEquals(raw, MeshCrypto.unb64(encoded))
    }

    // =================== Multiple encrypt/decrypt cycles ===================

    @Test
    fun multipleEncryptDecrypt_cycles() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val secret = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)

        for (i in 1..50) {
            val plain = "Message $i".toByteArray(Charsets.UTF_8)
            val ct = MeshCrypto.encrypt(secret, plain)
            val dt = MeshCrypto.decrypt(secret, ct)
            assertArrayEquals(plain, dt)
        }
    }

    @Test
    fun interleavedEncryptDecrypt() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val secret = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)

        for (i in 1..25) {
            val ct = MeshCrypto.encrypt(secret, "msg$i".toByteArray())
            val dt = MeshCrypto.decrypt(secret, ct)
            assertNotNull(dt)
        }
    }

    // =================== Wrong key ===================

    @Test
    fun decrypt_withWrongSharedSecret_fails() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val c = MeshCrypto.generateKeyPair()
        val secretAB = MeshCrypto.computeSharedSecret(a.privateKey, b.publicKey)
        val secretAC = MeshCrypto.computeSharedSecret(a.privateKey, c.publicKey)

        val ct = MeshCrypto.encrypt(secretAB, "secret".toByteArray())
        assertThrows(Exception::class.java) {
            MeshCrypto.decrypt(secretAC, ct)
        }
    }

    // =================== KeyPair data class ===================

    @Test
    fun keyPair_fieldsAccessible() {
        val kp = MeshCrypto.generateKeyPair()
        assertEquals(32, kp.privateKey.size)
        assertEquals(32, kp.publicKey.size)
    }
}
