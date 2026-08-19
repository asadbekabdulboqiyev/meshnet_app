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

class DoubleRatchetStressTest {

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

    @Test
    fun hundredMessageChain_allDecrypt() {
        val (alice, bob) = createAliceAndBob()
        for (i in 1..100) {
            val ct = alice.encrypt("msg$i".toByteArray())
            val pt = bob.decrypt(ct)
            assertNotNull(pt)
            assertEquals("msg$i", String(pt!!))
        }
    }

    @Test
    fun hundredMessageChain_sendCountIs100() {
        val (alice, _) = createAliceAndBob()
        for (i in 1..100) {
            alice.encrypt("msg$i".toByteArray())
        }
        assertEquals(100, alice.getSendCount())
    }

    @Test
    fun hundredMessageChain_shouldRotateTrueAfter101() {
        val (alice, _) = createAliceAndBob()
        for (i in 1..100) {
            alice.encrypt("msg$i".toByteArray())
        }
        assertFalse(alice.shouldRotate())
        alice.encrypt("final".toByteArray())
        assertTrue(alice.shouldRotate())
    }

    @Test
    fun serialize_deserialize_restoresState() {
        val (alice, bob) = createAliceAndBob()
        for (i in 1..10) {
            val ct = alice.encrypt("msg$i".toByteArray())
            bob.decrypt(ct)
        }
        val data = alice.serialize()
        val newAlice = DoubleRatchet(
            MeshCrypto.computeSharedSecret(
                MeshCrypto.generateKeyPair().privateKey,
                MeshCrypto.generateKeyPair().publicKey
            ),
            DoubleRatchet.generateKeyPair(),
            DoubleRatchet.generateKeyPair().publicKey,
        )
        newAlice.deserialize(data)
        val ct = newAlice.encrypt("restored".toByteArray())
        val pt = bob.decrypt(ct)
        assertNotNull(pt)
        assertEquals("restored", String(pt!!))
    }

    @Test
    fun serialize_deserialize_bobState() {
        val (alice, bob) = createAliceAndBob()
        val cts = (1..5).map { alice.encrypt("msg$it".toByteArray()) }
        bob.decrypt(cts[0])
        val data = bob.serialize()
        val newBob = DoubleRatchet(
            MeshCrypto.computeSharedSecret(
                MeshCrypto.generateKeyPair().privateKey,
                MeshCrypto.generateKeyPair().publicKey
            ),
            DoubleRatchet.generateKeyPair(),
            DoubleRatchet.generateKeyPair().publicKey,
        )
        newBob.deserialize(data)
        assertNotNull(newBob.decrypt(cts[1]))
        assertNotNull(newBob.decrypt(cts[2]))
    }

    @Test
    fun multipleSessions_independent() {
        val pairs = (1..5).map { createAliceAndBob() }
        for ((idx, pair) in pairs.withIndex()) {
            val (alice, bob) = pair
            val ct = alice.encrypt("session-$idx".toByteArray())
            val pt = bob.decrypt(ct)
            assertNotNull(pt)
            assertEquals("session-$idx", String(pt!!))
        }
    }

    @Test
    fun multipleSessions_differentSharedSecrets() {
        val (alice1, bob1) = createAliceAndBob()
        val (alice2, bob2) = createAliceAndBob()
        val ct1 = alice1.encrypt("secret1".toByteArray())
        val ct2 = alice2.encrypt("secret2".toByteArray())
        assertEquals("secret1", String(bob1.decrypt(ct1)!!))
        assertEquals("secret2", String(bob2.decrypt(ct2)!!))
    }

    @Test
    fun outOfOrderMessages_firstThenThird() {
        val (alice, bob) = createAliceAndBob()
        val ct1 = alice.encrypt("first".toByteArray())
        val ct2 = alice.encrypt("second".toByteArray())
        val ct3 = alice.encrypt("third".toByteArray())
        val p1 = bob.decrypt(ct1)
        val p3 = bob.decrypt(ct3)
        assertNotNull(p1)
        assertNotNull(p3)
        assertEquals("first", String(p1!!))
        assertEquals("third", String(p3!!))
    }

    @Test
    fun outOfOrderMessages_skipMiddle() {
        val (alice, bob) = createAliceAndBob()
        val ct1 = alice.encrypt("a".toByteArray())
        val ct2 = alice.encrypt("b".toByteArray())
        val ct3 = alice.encrypt("c".toByteArray())
        val ct4 = alice.encrypt("d".toByteArray())
        bob.decrypt(ct1)
        bob.decrypt(ct4)
        val p2 = bob.decrypt(ct2)
        val p3 = bob.decrypt(ct3)
        assertNotNull(p2)
        assertNotNull(p3)
    }

    @Test
    fun serialization_roundtrip_100Messages() {
        val (alice, bob) = createAliceAndBob()
        for (i in 1..50) {
            val ct = alice.encrypt("msg$i".toByteArray())
            bob.decrypt(ct)
        }
        val data = alice.serialize()
        val newAlice = DoubleRatchet(
            MeshCrypto.computeSharedSecret(
                MeshCrypto.generateKeyPair().privateKey,
                MeshCrypto.generateKeyPair().publicKey
            ),
            DoubleRatchet.generateKeyPair(),
            DoubleRatchet.generateKeyPair().publicKey,
        )
        newAlice.deserialize(data)
        for (i in 51..100) {
            val ct = newAlice.encrypt("msg$i".toByteArray())
            val pt = bob.decrypt(ct)
            assertNotNull(pt)
            assertEquals("msg$i", String(pt!!))
        }
    }

    @Test
    fun largeMessage_50KB() {
        val (alice, bob) = createAliceAndBob()
        val plain = ByteArray(50_000) { (it % 256).toByte() }
        val ct = alice.encrypt(plain)
        val pt = bob.decrypt(ct)
        assertNotNull(pt)
        assertArrayEquals(plain, pt!!)
    }

    @Test
    fun encrypt_producesDifferentCiphertextEachTime() {
        val (alice, _) = createAliceAndBob()
        val ct1 = alice.encrypt("same".toByteArray())
        val ct2 = alice.encrypt("same".toByteArray())
        assertFalse(ct1.contentEquals(ct2))
    }

    @Test
    fun getSendPublicKey_afterMessages_returnsKey() {
        val (alice, _) = createAliceAndBob()
        for (i in 1..10) {
            alice.encrypt("msg$i".toByteArray())
        }
        val pk = alice.getSendPublicKey()
        assertEquals(32, pk.size)
    }

    @Test
    fun generateKeyPair_10KeyPairs_allUnique() {
        val pairs = (1..10).map { DoubleRatchet.generateKeyPair() }
        val publicKeys = pairs.map { it.publicKey.toList() }.toSet()
        assertEquals(10, publicKeys.size)
    }

    @Test
    fun kdfRootKey_variousInputs_allReturn32Bytes() {
        for (i in 1..10) {
            val rk = ByteArray(32) { (it + i).toByte() }
            val dh = ByteArray(32) { (it * i).toByte() }
            val (newRk, ck) = DoubleRatchet.kdfRootKey(rk, dh)
            assertEquals(32, newRk.size)
            assertEquals(32, ck.size)
        }
    }

    @Test
    fun kdfChainKey_variousInputs_allReturn32Bytes() {
        for (i in 1..10) {
            val ck = ByteArray(32) { (it + i).toByte() }
            val (newCk, mk) = DoubleRatchet.kdfChainKey(ck)
            assertEquals(32, newCk.size)
            assertEquals(32, mk.size)
        }
    }

    @Test
    fun dh_keyExchange_symmetry() {
        val kp1 = DoubleRatchet.generateKeyPair()
        val kp2 = DoubleRatchet.generateKeyPair()
        val s1 = DoubleRatchet.dh(kp1.privateKey, kp2.publicKey)
        val s2 = DoubleRatchet.dh(kp2.privateKey, kp1.publicKey)
        assertArrayEquals(s1, s2)
    }

    @Test
    fun dh_differentPairs_differentSecrets() {
        val kp1 = DoubleRatchet.generateKeyPair()
        val kp2 = DoubleRatchet.generateKeyPair()
        val kp3 = DoubleRatchet.generateKeyPair()
        val s1 = DoubleRatchet.dh(kp1.privateKey, kp2.publicKey)
        val s2 = DoubleRatchet.dh(kp1.privateKey, kp3.publicKey)
        assertFalse(s1.contentEquals(s2))
    }

    @Test
    fun decrypt_garbage_returnsNull() {
        val (_, bob) = createAliceAndBob()
        assertNull(bob.decrypt(ByteArray(5)))
    }

    @Test
    fun decrypt_empty_returnsNull() {
        val (_, bob) = createAliceAndBob()
        assertNull(bob.decrypt(ByteArray(0)))
    }

    @Test
    fun encrypt_emptyArray_roundtrip() {
        val (alice, bob) = createAliceAndBob()
        val ct = alice.encrypt(ByteArray(0))
        val pt = bob.decrypt(ct)
        assertNotNull(pt)
        assertEquals(0, pt!!.size)
    }

    @Test
    fun serialize_emptySession_works() {
        val (alice, _) = createAliceAndBob()
        val data = alice.serialize()
        assertNotNull(data)
        assertTrue(data.isNotEmpty())
    }

    @Test
    fun serialize_afterMessages_largerThanEmpty() {
        val (alice, _) = createAliceAndBob()
        val emptyData = alice.serialize()
        for (i in 1..10) {
            alice.encrypt("msg$i".toByteArray())
        }
        val afterData = alice.serialize()
        assertTrue(afterData.size >= emptyData.size)
    }

    @Test
    fun skippedKeyIndex_sameHash_forEqualObjects() {
        val key = ByteArray(32) { it.toByte() }
        val i1 = DoubleRatchet.SkippedKeyIndex(key, 42)
        val i2 = DoubleRatchet.SkippedKeyIndex(key, 42)
        assertEquals(i1.hashCode(), i2.hashCode())
    }

    @Test
    fun skippedKeyIndex_differentKey_differentHash() {
        val k1 = ByteArray(32) { 0x01 }
        val k2 = ByteArray(32) { 0x02 }
        val i1 = DoubleRatchet.SkippedKeyIndex(k1, 42)
        val i2 = DoubleRatchet.SkippedKeyIndex(k2, 42)
        // Very likely different
        assertFalse(i1.hashCode() == i2.hashCode())
    }

    @Test
    fun skippedKeyIndex_differentMessageNum_notEqual() {
        val key = ByteArray(32) { 0x01 }
        val i1 = DoubleRatchet.SkippedKeyIndex(key, 1)
        val i2 = DoubleRatchet.SkippedKeyIndex(key, 2)
        assertFalse(i1 == i2)
    }

    @Test
    fun encrypt_singleByte_roundtrip() {
        val (alice, bob) = createAliceAndBob()
        val ct = alice.encrypt(byteArrayOf(0x42))
        val pt = bob.decrypt(ct)
        assertNotNull(pt)
        assertEquals(1, pt!!.size)
        assertEquals(0x42.toByte(), pt[0])
    }

    @Test
    fun bidirectional_200Messages_alternating() {
        val (alice, bob) = createAliceAndBob()
        for (i in 1..100) {
            val a2b = alice.encrypt("A:$i".toByteArray())
            assertEquals("A:$i", String(bob.decrypt(a2b)!!))
            val b2a = bob.encrypt("B:$i".toByteArray())
            assertEquals("B:$i", String(alice.decrypt(b2a)!!))
        }
    }
}
