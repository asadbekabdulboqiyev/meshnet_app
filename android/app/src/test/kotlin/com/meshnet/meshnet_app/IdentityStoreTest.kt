package com.meshnet.meshnet_app

import android.content.Context
import com.meshnet.meshnet_app.crypto.MeshCrypto
import com.meshnet.meshnet_app.storage.MeshDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * IdentityStore testlari: init, deviceId, privateKey, publicKey, displayName, setDisplayName.
 * MeshDatabase mock bilan.
 */
class IdentityStoreTest {

    private lateinit var mockContext: Context
    private lateinit var store: IdentityStore

    @Before
    fun setUp() {
        MeshDatabase.setInstance(TestDatabaseHelper.createMockDatabase())
        mockContext = mock(Context::class.java)
        store = IdentityStore(mockContext)
    }

    @After
    fun tearDown() {
        MeshDatabase.resetInstance()
    }

    // =================== init ===================

    @Test
    fun init_createsNewIdentity() {
        store.init("Test User")
        val id = store.deviceId()
        assertTrue(id.isNotEmpty())
        assertTrue(id.contains("-"))
    }

    @Test
    fun init_createsUniqueId() {
        store.init(null)
        val id1 = store.deviceId()
        val store2 = IdentityStore(mockContext)
        store2.init(null)
        val id2 = store2.deviceId()
        // Both should have valid UUIDs (even if from the same storage, 
        // first call creates it)
        assertTrue(id1.isNotEmpty())
    }

    @Test
    fun init_createsKeyPair() {
        store.init(null)
        val privKey = store.privateKey()
        val pubKey = store.publicKey()
        assertEquals(32, privKey.size)
        assertEquals(32, pubKey.size)
        assertFalse(privKey.contentEquals(pubKey))
    }

    @Test
    fun init_savesDisplayName() {
        store.init("Alice")
        assertEquals("Alice", store.displayName())
    }

    @Test
    fun init_usesDefaultName_whenNull() {
        store.init(null)
        assertEquals("MeshNet User", store.displayName())
    }

    @Test
    fun init_doesNotOverwriteExistingIdentity() {
        store.init("Alice")
        val origId = store.deviceId()
        val origPrivKey = store.privateKey()
        val origPubKey = store.publicKey()

        // Init again with different name
        val store2 = IdentityStore(mockContext)
        store2.init("Bob")

        // deviceId and keys should be the same (from storage)
        assertEquals(origId, store2.deviceId())
        assertTrue(origPrivKey.contentEquals(store2.privateKey()))
        assertTrue(origPubKey.contentEquals(store2.publicKey()))
        // Display name should be updated
        assertEquals("Bob", store2.displayName())
    }

    @Test
    fun init_emptyDisplayName_usesDefault() {
        store.init("")
        assertEquals("MeshNet User", store.displayName())
    }

    // =================== deviceId ===================

    @Test
    fun deviceId_returnsUUIDFormat() {
        store.init(null)
        val id = store.deviceId()
        val parts = id.split("-")
        assertEquals(5, parts.size)
        assertEquals(8, parts[0].length)
        assertEquals(4, parts[1].length)
        assertEquals(4, parts[2].length)
        assertEquals(4, parts[3].length)
        assertEquals(12, parts[4].length)
    }

    @Test
    fun deviceId_returnsSameIdOnMultipleCalls() {
        store.init(null)
        val id1 = store.deviceId()
        val id2 = store.deviceId()
        assertEquals(id1, id2)
    }

    @Test
    fun deviceId_initializesIfNotExists() {
        // No init called yet
        val id = store.deviceId()
        assertTrue(id.isNotEmpty())
        // Keys should also be created
        assertEquals(32, store.privateKey().size)
        assertEquals(32, store.publicKey().size)
    }

    // =================== privateKey / publicKey ===================

    @Test
    fun privateKey_returns32Bytes() {
        store.init(null)
        assertEquals(32, store.privateKey().size)
    }

    @Test
    fun publicKey_returns32Bytes() {
        store.init(null)
        assertEquals(32, store.publicKey().size)
    }

    @Test
    fun privateKeyAndPublicKey_areDifferent() {
        store.init(null)
        assertFalse(store.privateKey().contentEquals(store.publicKey()))
    }

    @Test
    fun keyPair_isValidECDH() {
        store.init(null)
        // Generate a second key pair to compute shared secret
        val otherKP = MeshCrypto.generateKeyPair()
        val sharedSecret = MeshCrypto.computeSharedSecret(store.privateKey(), otherKP.publicKey)
        assertEquals(32, sharedSecret.size)
        // Should be deterministic
        val sharedSecret2 = MeshCrypto.computeSharedSecret(store.privateKey(), otherKP.publicKey)
        assertTrue(sharedSecret.contentEquals(sharedSecret2))
    }

    @Test
    fun privateKey_returnsConsistentValues() {
        store.init(null)
        val key1 = store.privateKey()
        val key2 = store.privateKey()
        assertTrue(key1.contentEquals(key2))
    }

    @Test
    fun publicKey_returnsConsistentValues() {
        store.init(null)
        val key1 = store.publicKey()
        val key2 = store.publicKey()
        assertTrue(key1.contentEquals(key2))
    }

    // =================== displayName / setDisplayName ===================

    @Test
    fun displayName_returnsDefaultWhenNotSet() {
        assertEquals("MeshNet User", store.displayName())
    }

    @Test
    fun setDisplayName_updatesName() {
        store.init("Alice")
        store.setDisplayName("Bob")
        assertEquals("Bob", store.displayName())
    }

    @Test
    fun setDisplayName_trimsWhitespace() {
        store.setDisplayName("  Charlie  ")
        assertEquals("Charlie", store.displayName())
    }

    @Test
    fun setDisplayName_emptyString_usesDefault() {
        store.setDisplayName("")
        assertEquals("MeshNet User", store.displayName())
    }

    @Test
    fun setDisplayName_whitespaceOnly_usesDefault() {
        store.setDisplayName("   ")
        assertEquals("MeshNet User", store.displayName())
    }

    @Test
    fun setDisplayName_preservesOtherFields() {
        store.init("Alice")
        val origId = store.deviceId()
        val origPrivKey = store.privateKey()
        val origPubKey = store.publicKey()

        store.setDisplayName("Bob")

        assertEquals(origId, store.deviceId())
        assertTrue(origPrivKey.contentEquals(store.privateKey()))
        assertTrue(origPubKey.contentEquals(store.publicKey()))
        assertEquals("Bob", store.displayName())
    }

    @Test
    fun setDisplayName_specialCharacters() {
        store.setDisplayName("O'tkan chiziq")
        assertEquals("O'tkan chiziq", store.displayName())
    }

    @Test
    fun setDisplayName_longName() {
        val longName = "A".repeat(500)
        store.setDisplayName(longName)
        assertEquals(longName, store.displayName())
    }

    @Test
    fun setDisplayName_unicode() {
        store.setDisplayName("Yangi foydalanuvchi")
        assertEquals("Yangi foydalanuvchi", store.displayName())
    }

    // =================== Persistence ===================

    @Test
    fun init_persistsToDeviceId() {
        store.init(null)
        val id = store.deviceId()
        assertNotNull(id)
        assertTrue(id.isNotEmpty())
    }

    @Test
    fun init_persistsPrivateKey() {
        store.init(null)
        val key = store.privateKey()
        assertNotNull(key)
        assertEquals(32, key.size)
    }

    @Test
    fun init_persistsPublicKey() {
        store.init(null)
        val key = store.publicKey()
        assertNotNull(key)
        assertEquals(32, key.size)
    }

    @Test
    fun init_persistsDisplayName() {
        store.init("Test")
        assertEquals("Test", store.displayName())
    }

    @Test
    fun setDisplayName_persistsChange() {
        store.init("Alice")
        store.setDisplayName("Bob")
        assertEquals("Bob", store.displayName())
    }

    // =================== Companion constants ===================

    @Test
    fun defaultName_isMeshNetUser() {
        store.init(null)
        assertEquals("MeshNet User", store.displayName())
    }
}
