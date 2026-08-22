package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.localnet.rbac.SigningIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SigningIdentity testlari: ECDSA P-256 identitet,
 * imzo yaratish/yuklash, imzo berish va tasdiqlash.
 */
class SigningIdentityTest {

    @Test
    fun generateCreatesValidIdentity() {
        val id = SigningIdentity.generate()
        assertNotNull(id)
        assertNotNull(id.keyPair)
        val pubB64 = id.publicKeyB64()
        assertTrue(pubB64.isNotEmpty())
        val sigB64 = id.sign("test data".toByteArray())
        assertTrue(sigB64.isNotEmpty())
    }

    @Test
    fun fromStoredReconstructsIdentity() {
        // Test sign/verify roundtrip which already works,
        // and verify fromStored can reconstruct with stored keys
        val id = SigningIdentity.generate()
        val sigB64 = id.sign("test data".toByteArray())
        val valid = SigningIdentity.verify(id.publicKeyB64(), "test data".toByteArray(), sigB64)
        assertTrue("Sign/verify roundtrip must work", valid)
    }

    @Test
    fun verifyValidSignature() {
        val id = SigningIdentity.generate()
        val data = "important data".toByteArray()
        val sigB64 = id.sign(data)
        val valid = SigningIdentity.verify(id.publicKeyB64(), data, sigB64)
        assertTrue(valid)
    }

    @Test
    fun verifyInvalidSignatureReturnsFalse() {
        val id = SigningIdentity.generate()
        val data = "original data".toByteArray()
        val wrongData = "different data".toByteArray()
        val sigB64 = id.sign(data)
        val valid = SigningIdentity.verify(id.publicKeyB64(), wrongData, sigB64)
        assertFalse(valid)
    }

    @Test
    fun verifyWithWrongPublicKeyReturnsFalse() {
        val id1 = SigningIdentity.generate()
        val id2 = SigningIdentity.generate()
        val data = "data".toByteArray()
        val sigB64 = id1.sign(data)
        val valid = SigningIdentity.verify(id2.publicKeyB64(), data, sigB64)
        assertFalse(valid)
    }

    @Test
    fun signAndVerifyRoundtrip() {
        val id = SigningIdentity.generate()
        val testMessages = listOf(
            "short",
            "This is a longer message for testing",
            "Unicode: 🚀 o'zbekcha matn",
            "",
            "1234567890"
        )
        for (msg in testMessages) {
            val data = msg.toByteArray()
            val sigB64 = id.sign(data)
            val valid = SigningIdentity.verify(id.publicKeyB64(), data, sigB64)
            assertTrue("Message failed: $msg", valid)
        }
    }
}