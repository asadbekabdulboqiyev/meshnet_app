package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.crypto.MeshCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MeshCrypto stress testlari: edge cases, performance, boundary conditions.
 */
class CryptoStressTest {

    // =================== Key generation ===================

    @Test
    fun generateKeyPair_returnsValidKeys() {
        val kp = MeshCrypto.generateKeyPair()
        assertEquals(32, kp.publicKey.size)
        assertEquals(32, kp.privateKey.size)
    }

    @Test
    fun generateKeyPair_uniqueKeys() {
        val kp1 = MeshCrypto.generateKeyPair()
        val kp2 = MeshCrypto.generateKeyPair()
        assertFalse(kp1.publicKey.contentEquals(kp2.publicKey))
        assertFalse(kp1.privateKey.contentEquals(kp2.privateKey))
    }

    @Test
    fun generateKeyPair_100Pairs_allUnique() {
        val pairs = (1..100).map { MeshCrypto.generateKeyPair() }
        val pubKeys = pairs.map { it.publicKey.toList() }.toSet()
        assertEquals(100, pubKeys.size)
    }

    // =================== Shared secret ===================

    @Test
    fun sharedSecret_dhProperty() {
        val kpA = MeshCrypto.generateKeyPair()
        val kpB = MeshCrypto.generateKeyPair()
        val secretA = MeshCrypto.computeSharedSecret(kpA.privateKey, kpB.publicKey)
        val secretB = MeshCrypto.computeSharedSecret(kpB.privateKey, kpA.publicKey)
        assertArrayEquals(secretA, secretB)
    }

    @Test
    fun sharedSecret_32Bytes() {
        val kpA = MeshCrypto.generateKeyPair()
        val kpB = MeshCrypto.generateKeyPair()
        val secret = MeshCrypto.computeSharedSecret(kpA.privateKey, kpB.publicKey)
        assertEquals(32, secret.size)
    }

    @Test
    fun sharedSecret_differentParties_differentSecrets() {
        val kpA = MeshCrypto.generateKeyPair()
        val kpB = MeshCrypto.generateKeyPair()
        val kpC = MeshCrypto.generateKeyPair()
        val secretAB = MeshCrypto.computeSharedSecret(kpA.privateKey, kpB.publicKey)
        val secretAC = MeshCrypto.computeSharedSecret(kpA.privateKey, kpC.publicKey)
        assertFalse(secretAB.contentEquals(secretAC))
    }

    // =================== Encrypt/Decrypt ===================

    @Test
    fun encryptDecrypt_roundtrip() {
        val kpA = MeshCrypto.generateKeyPair()
        val kpB = MeshCrypto.generateKeyPair()
        val shared = MeshCrypto.computeSharedSecret(kpA.privateKey, kpB.publicKey)
        val plaintext = "Hello MeshNet!".toByteArray()
        val aad = "test-aad".toByteArray()
        val ciphertext = MeshCrypto.encrypt(shared, plaintext, aad)
        val decrypted = MeshCrypto.decrypt(shared, ciphertext, aad)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun encryptDecrypt_emptyPayload() {
        val kp = MeshCrypto.generateKeyPair()
        val shared = MeshCrypto.computeSharedSecret(kp.privateKey, kp.publicKey)
        val plaintext = ByteArray(0)
        val aad = ByteArray(0)
        val ciphertext = MeshCrypto.encrypt(shared, plaintext, aad)
        val decrypted = MeshCrypto.decrypt(shared, ciphertext, aad)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun encryptDecrypt_largePayload() {
        val kp = MeshCrypto.generateKeyPair()
        val shared = MeshCrypto.computeSharedSecret(kp.privateKey, kp.publicKey)
        val plaintext = ByteArray(1024 * 10) { (it % 256).toByte() }
        val aad = "large".toByteArray()
        val ciphertext = MeshCrypto.encrypt(shared, plaintext, aad)
        val decrypted = MeshCrypto.decrypt(shared, ciphertext, aad)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun encryptDecrypt_samePlaintext_differentCiphertext() {
        val kp = MeshCrypto.generateKeyPair()
        val shared = MeshCrypto.computeSharedSecret(kp.privateKey, kp.publicKey)
        val plaintext = "same".toByteArray()
        val aad = ByteArray(0)
        val ct1 = MeshCrypto.encrypt(shared, plaintext, aad)
        val ct2 = MeshCrypto.encrypt(shared, plaintext, aad)
        // Different nonce -> different ciphertext
        assertFalse(ct1.contentEquals(ct2))
    }

    @Test
    fun encryptDecrypt_wrongKey_fails() {
        val kpA = MeshCrypto.generateKeyPair()
        val kpB = MeshCrypto.generateKeyPair()
        val kpC = MeshCrypto.generateKeyPair()
        val sharedAB = MeshCrypto.computeSharedSecret(kpA.privateKey, kpB.publicKey)
        val sharedAC = MeshCrypto.computeSharedSecret(kpA.privateKey, kpC.publicKey)
        val plaintext = "secret".toByteArray()
        val aad = ByteArray(0)
        val ciphertext = MeshCrypto.encrypt(sharedAB, plaintext, aad)
        try {
            MeshCrypto.decrypt(sharedAC, ciphertext, aad)
            assertTrue("Should have thrown", false)
        } catch (e: Exception) {
            // Expected
        }
    }

    @Test
    fun encryptDecrypt_wrongAad_fails() {
        val kp = MeshCrypto.generateKeyPair()
        val shared = MeshCrypto.computeSharedSecret(kp.privateKey, kp.publicKey)
        val plaintext = "secret".toByteArray()
        val ciphertext = MeshCrypto.encrypt(shared, plaintext, "correct-aad".toByteArray())
        try {
            MeshCrypto.decrypt(shared, ciphertext, "wrong-aad".toByteArray())
            assertTrue("Should have thrown", false)
        } catch (e: Exception) {
            // Expected
        }
    }

    @Test
    fun encryptDecrypt_tamperedCiphertext_fails() {
        val kp = MeshCrypto.generateKeyPair()
        val shared = MeshCrypto.computeSharedSecret(kp.privateKey, kp.publicKey)
        val plaintext = "data".toByteArray()
        val aad = ByteArray(0)
        val ciphertext = MeshCrypto.encrypt(shared, plaintext, aad)
        // Tamper last byte
        val tampered = ciphertext.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xFF).toByte()
        try {
            MeshCrypto.decrypt(shared, tampered, aad)
            assertTrue("Should have thrown", false)
        } catch (e: Exception) {
            // Expected
        }
    }

    // =================== Base64 ===================

    @Test
    fun b64_roundtrip() {
        val data = ByteArray(256) { it.toByte() }
        val encoded = MeshCrypto.b64(data)
        val decoded = MeshCrypto.unb64(encoded)
        assertArrayEquals(data, decoded)
    }

    @Test
    fun b64_emptyData() {
        val data = ByteArray(0)
        val encoded = MeshCrypto.b64(data)
        val decoded = MeshCrypto.unb64(encoded)
        assertArrayEquals(data, decoded)
    }

    @Test
    fun b64_invalidString_throws() {
        try {
            MeshCrypto.unb64("not-valid-base64!!!")
            assertTrue("Should have thrown", false)
        } catch (e: Exception) {
            // Expected
        }
    }

    // =================== Performance ===================

    @Test
    fun performance_keyGen_1000Keys() {
        val start = System.currentTimeMillis()
        repeat(1000) { MeshCrypto.generateKeyPair() }
        val elapsed = System.currentTimeMillis() - start
        assertTrue("1000 keygen should take < 10s, took ${elapsed}ms", elapsed < 10000)
    }

    @Test
    fun performance_encryptDecrypt_1000Ops() {
        val kp = MeshCrypto.generateKeyPair()
        val shared = MeshCrypto.computeSharedSecret(kp.privateKey, kp.publicKey)
        val plaintext = "benchmark".toByteArray()
        val aad = ByteArray(0)
        val start = System.currentTimeMillis()
        repeat(1000) {
            val ct = MeshCrypto.encrypt(shared, plaintext, aad)
            MeshCrypto.decrypt(shared, ct, aad)
        }
        val elapsed = System.currentTimeMillis() - start
        assertTrue("1000 encrypt+decrypt should take < 15s, took ${elapsed}ms", elapsed < 15000)
    }

    // =================== Key sizes ===================

    @Test
    fun keySizes_all32Bytes() {
        repeat(10) {
            val kp = MeshCrypto.generateKeyPair()
            assertEquals(32, kp.publicKey.size)
            assertEquals(32, kp.privateKey.size)
        }
    }
}
