package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.crypto.DoubleRatchet
import com.meshnet.meshnet_app.crypto.MeshCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DoubleRatchet kengaytirilgan testlari: edge cases, empty messages,
 * large messages, key properties, concurrent operations.
 */
class DoubleRatchetExtendedTest {

    private fun createAliceAndBob(): Pair<DoubleRatchet, DoubleRatchet> {
        val sharedSecret = MeshCrypto.computeSharedSecret(
            MeshCrypto.generateKeyPair().privateKey,
            MeshCrypto.generateKeyPair().publicKey
        )
        val aliceDh = DoubleRatchet.generateKeyPair()
        val bobDh = DoubleRatchet.generateKeyPair()
        val alice = DoubleRatchet(sharedSecret, aliceDh, bobDh.publicKey)
        val bob = DoubleRatchet(sharedSecret, bobDh, aliceDh.publicKey)
        return alice to bob
    }

    // =================== Empty and boundary messages ===================

    @Test
    fun encryptDecrypt_emptyByteArray() {
        val (alice, bob) = createAliceAndBob()
        val ct = alice.encrypt(ByteArray(0))
        val dt = bob.decrypt(ct)
        assertNotNull(dt)
        assertEquals(0, dt!!.size)
    }

    @Test
    fun encryptDecrypt_singleByte() {
        val (alice, bob) = createAliceAndBob()
        val plain = byteArrayOf(0x42)
        val ct = alice.encrypt(plain)
        val dt = bob.decrypt(ct)
        assertNotNull(dt)
        assertArrayEquals(plain, dt!!)
    }

    @Test
    fun encryptDecrypt_largeMessage() {
        val (alice, bob) = createAliceAndBob()
        val plain = ByteArray(50000) { (it % 256).toByte() }
        val ct = alice.encrypt(plain)
        val dt = bob.decrypt(ct)
        assertNotNull(dt)
        assertArrayEquals(plain, dt!!)
    }

    @Test
    fun encryptDecrypt_binaryMessage() {
        val (alice, bob) = createAliceAndBob()
        val plain = ByteArray(256) { it.toByte() }
        plain[0] = 0x00
        plain[128] = 0xFF.toByte()
        val ct = alice.encrypt(plain)
        val dt = bob.decrypt(ct)
        assertNotNull(dt)
        assertArrayEquals(plain, dt!!)
    }

    @Test
    fun encryptDecrypt_unicodeMessage() {
        val (alice, bob) = createAliceAndBob()
        val text = "Yangi yil muborak bo'lsin!"
        val ct = alice.encrypt(text.toByteArray(Charsets.UTF_8))
        val dt = bob.decrypt(ct)
        assertNotNull(dt)
        assertEquals(text, String(dt!!, Charsets.UTF_8))
    }

    // =================== Send count ===================

    @Test
    fun getSendCount_initiallyZero() {
        val (alice, _) = createAliceAndBob()
        assertEquals(0, alice.getSendCount())
    }

    @Test
    fun getSendCount_incrementsWithEachMessage() {
        val (alice, _) = createAliceAndBob()
        for (i in 1..10) {
            alice.encrypt("msg$i".toByteArray())
            assertEquals(i, alice.getSendCount())
        }
    }

    // =================== shouldRotate ===================

    @Test
    fun shouldRotate_initiallyFalse() {
        val (alice, _) = createAliceAndBob()
        assertFalse(alice.shouldRotate())
    }

    @Test
    fun shouldRotate_trueAfter101Messages() {
        val (alice, bob) = createAliceAndBob()
        for (i in 1..100) {
            val ct = alice.encrypt("msg$i".toByteArray())
            bob.decrypt(ct)
        }
        assertFalse(alice.shouldRotate())
        alice.encrypt("final".toByteArray())
        assertTrue(alice.shouldRotate())
    }

    // =================== getSendPublicKey ===================

    @Test
    fun getSendPublicKey_returns32Bytes() {
        val (alice, _) = createAliceAndBob()
        val pk = alice.getSendPublicKey()
        assertEquals(32, pk.size)
    }

    @Test
    fun getSendPublicKey_returnsCopy() {
        val (alice, _) = createAliceAndBob()
        val pk1 = alice.getSendPublicKey()
        val pk2 = alice.getSendPublicKey()
        assertTrue(pk1.contentEquals(pk2))
        assertFalse(pk1 === pk2) // Different object references
    }

    // =================== Serialization ===================

    @Test
    fun serialize_emptySession() {
        val (alice, _) = createAliceAndBob()
        val data = alice.serialize()
        assertNotNull(data)
        assertTrue(data.isNotEmpty())
    }

    @Test
    fun serialize_differentSessions_differentOutput() {
        val a1 = DoubleRatchet(
            MeshCrypto.computeSharedSecret(
                MeshCrypto.generateKeyPair().privateKey,
                MeshCrypto.generateKeyPair().publicKey
            ),
            DoubleRatchet.generateKeyPair(),
            DoubleRatchet.generateKeyPair().publicKey,
        )
        val a2 = DoubleRatchet(
            MeshCrypto.computeSharedSecret(
                MeshCrypto.generateKeyPair().privateKey,
                MeshCrypto.generateKeyPair().publicKey
            ),
            DoubleRatchet.generateKeyPair(),
            DoubleRatchet.generateKeyPair().publicKey,
        )
        assertFalse(a1.serialize().contentEquals(a2.serialize()))
    }

    @Test
    fun deserialize_afterSerialize_canDecrypt() {
        val (alice, bob) = createAliceAndBob()

        // Send some messages
        for (i in 1..5) {
            val ct = alice.encrypt("msg$i".toByteArray())
            val dt = bob.decrypt(ct)
            assertNotNull(dt)
        }

        // Serialize alice
        val data = alice.serialize()

        // Create new alice and deserialize
        val newAlice = DoubleRatchet(
            MeshCrypto.computeSharedSecret(
                MeshCrypto.generateKeyPair().privateKey,
                MeshCrypto.generateKeyPair().publicKey
            ),
            DoubleRatchet.generateKeyPair(),
            DoubleRatchet.generateKeyPair().publicKey,
        )
        newAlice.deserialize(data)

        // New alice can send and bob can decrypt
        val ct = newAlice.encrypt("after restore".toByteArray())
        val dt = bob.decrypt(ct)
        assertNotNull(dt)
        assertEquals("after restore", String(dt!!))
    }

    @Test
    fun deserialize_afterMessages_bobCanDecrypt() {
        val (alice, bob) = createAliceAndBob()

        // Alice sends messages
        val cts = (1..3).map { alice.encrypt("msg$it".toByteArray()) }

        // Bob decrypts only first message
        bob.decrypt(cts[0])

        // Serialize bob
        val data = bob.serialize()

        // Create new bob and deserialize
        val newBob = DoubleRatchet(
            MeshCrypto.computeSharedSecret(
                MeshCrypto.generateKeyPair().privateKey,
                MeshCrypto.generateKeyPair().publicKey
            ),
            DoubleRatchet.generateKeyPair(),
            DoubleRatchet.generateKeyPair().publicKey,
        )
        newBob.deserialize(data)

        // New bob can decrypt remaining messages
        val dt2 = newBob.decrypt(cts[1])
        assertNotNull(dt2)
        assertEquals("msg2", String(dt2!!))

        val dt3 = newBob.decrypt(cts[2])
        assertNotNull(dt3)
        assertEquals("msg3", String(dt3!!))
    }

    // =================== DHKeyPair ===================

    @Test
    fun generateKeyPair_returnsValidKeyPair() {
        val kp = DoubleRatchet.generateKeyPair()
        assertEquals(32, kp.privateKey.size)
        assertEquals(32, kp.publicKey.size)
    }

    @Test
    fun generateKeyPair_uniqueKeyPairs() {
        val kp1 = DoubleRatchet.generateKeyPair()
        val kp2 = DoubleRatchet.generateKeyPair()
        assertFalse(kp1.privateKey.contentEquals(kp2.privateKey))
        assertFalse(kp1.publicKey.contentEquals(kp2.publicKey))
    }

    // =================== SkippedKeyIndex ===================

    @Test
    fun skippedKeyIndex_equality() {
        val key = ByteArray(32) { it.toByte() }
        val idx1 = DoubleRatchet.SkippedKeyIndex(key, 42)
        val idx2 = DoubleRatchet.SkippedKeyIndex(key, 42)
        assertEquals(idx1, idx2)
    }

    @Test
    fun skippedKeyIndex_differentMessageNum_notEqual() {
        val key = ByteArray(32) { it.toByte() }
        val idx1 = DoubleRatchet.SkippedKeyIndex(key, 42)
        val idx2 = DoubleRatchet.SkippedKeyIndex(key, 43)
        assertFalse(idx1 == idx2)
    }

    @Test
    fun skippedKeyIndex_differentKey_notEqual() {
        val key1 = ByteArray(32) { 0x01 }
        val key2 = ByteArray(32) { 0x02 }
        val idx1 = DoubleRatchet.SkippedKeyIndex(key1, 42)
        val idx2 = DoubleRatchet.SkippedKeyIndex(key2, 42)
        assertFalse(idx1 == idx2)
    }

    @Test
    fun skippedKeyIndex_hashCode() {
        val key = ByteArray(32) { it.toByte() }
        val idx1 = DoubleRatchet.SkippedKeyIndex(key, 42)
        val idx2 = DoubleRatchet.SkippedKeyIndex(key, 42)
        assertEquals(idx1.hashCode(), idx2.hashCode())
    }

    @Test
    fun skippedKeyIndex_hashCode_differentForDifferentInputs() {
        val key = ByteArray(32) { it.toByte() }
        val idx1 = DoubleRatchet.SkippedKeyIndex(key, 42)
        val idx2 = DoubleRatchet.SkippedKeyIndex(key, 43)
        // Not guaranteed to be different but very likely
        assertFalse(idx1.hashCode() == idx2.hashCode())
    }

    // =================== Long conversation ===================

    @Test
    fun longConversation_bidirectional200Messages() {
        val (alice, bob) = createAliceAndBob()

        for (i in 1..200) {
            val a2b = alice.encrypt("A:$i".toByteArray())
            val bReceived = bob.decrypt(a2b)
            assertNotNull(bReceived)
            assertEquals("A:$i", String(bReceived!!))

            val b2a = bob.encrypt("B:$i".toByteArray())
            val aReceived = alice.decrypt(b2a)
            assertNotNull(aReceived)
            assertEquals("B:$i", String(aReceived!!))
        }
    }

    // =================== Decrypt returns null for garbage ===================

    @Test
    fun decrypt_garbage_returnsNull() {
        val (alice, bob) = createAliceAndBob()
        // Just random bytes that are too short
        val result = bob.decrypt(ByteArray(10))
        assertNull(result)
    }

    @Test
    fun decrypt_wrongSession_returnsNull() {
        val (alice1, bob1) = createAliceAndBob()
        val (_, bob2) = createAliceAndBob()

        val ct = alice1.encrypt("secret".toByteArray())
        val dt = bob2.decrypt(ct)
        assertNull(dt)
    }

    // =================== kdfRootKey / kdfChainKey ===================

    @Test
    fun kdfRootKey_returns32ByteKeys() {
        val rootKey = ByteArray(32) { it.toByte() }
        val dhOutput = ByteArray(32) { (it * 2).toByte() }
        val (newRoot, chainKey) = DoubleRatchet.kdfRootKey(rootKey, dhOutput)
        assertEquals(32, newRoot.size)
        assertEquals(32, chainKey.size)
    }

    @Test
    fun kdfRootKey_deterministic() {
        val rootKey = ByteArray(32) { it.toByte() }
        val dhOutput = ByteArray(32) { (it * 2).toByte() }
        val (r1, c1) = DoubleRatchet.kdfRootKey(rootKey, dhOutput)
        val (r2, c2) = DoubleRatchet.kdfRootKey(rootKey, dhOutput)
        assertArrayEquals(r1, r2)
        assertArrayEquals(c1, c2)
    }

    @Test
    fun kdfRootKey_differentInputsDifferentOutputs() {
        val rootKey = ByteArray(32) { it.toByte() }
        val (r1, c1) = DoubleRatchet.kdfRootKey(rootKey, ByteArray(32) { 0x01 })
        val (r2, c2) = DoubleRatchet.kdfRootKey(rootKey, ByteArray(32) { 0x02 })
        assertFalse(r1.contentEquals(r2))
        assertFalse(c1.contentEquals(c2))
    }

    @Test
    fun kdfChainKey_returns32ByteKeys() {
        val chainKey = ByteArray(32) { it.toByte() }
        val (newChain, messageKey) = DoubleRatchet.kdfChainKey(chainKey)
        assertEquals(32, newChain.size)
        assertEquals(32, messageKey.size)
    }

    @Test
    fun kdfChainKey_deterministic() {
        val chainKey = ByteArray(32) { it.toByte() }
        val (nc1, mk1) = DoubleRatchet.kdfChainKey(chainKey)
        val (nc2, mk2) = DoubleRatchet.kdfChainKey(chainKey)
        assertArrayEquals(nc1, nc2)
        assertArrayEquals(mk1, mk2)
    }

    @Test
    fun kdfChainKey_differentKeysDifferentOutputs() {
        val (nc1, mk1) = DoubleRatchet.kdfChainKey(ByteArray(32) { 0x01 })
        val (nc2, mk2) = DoubleRatchet.kdfChainKey(ByteArray(32) { 0x02 })
        assertFalse(nc1.contentEquals(nc2))
        assertFalse(mk1.contentEquals(mk2))
    }

    // =================== DH static method ===================

    @Test
    fun dh_isSymmetric() {
        val kp1 = DoubleRatchet.generateKeyPair()
        val kp2 = DoubleRatchet.generateKeyPair()
        val s1 = DoubleRatchet.dh(kp1.privateKey, kp2.publicKey)
        val s2 = DoubleRatchet.dh(kp2.privateKey, kp1.publicKey)
        assertArrayEquals(s1, s2)
    }

    @Test
    fun dh_returns32Bytes() {
        val kp = DoubleRatchet.generateKeyPair()
        val secret = DoubleRatchet.dh(kp.privateKey, kp.publicKey)
        assertEquals(32, secret.size)
    }
}
