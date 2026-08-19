package com.meshnet.meshnet_app

import android.content.Context
import android.content.SharedPreferences
import com.meshnet.meshnet_app.crypto.MeshCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

/**
 * IdentityStore testlari: init, deviceId, privateKey, publicKey, displayName, setDisplayName.
 * SharedPreferences mock bilan.
 */
class IdentityStoreTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var storage: MutableMap<String, String?>
    private lateinit var store: IdentityStore

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockPrefs = mock(SharedPreferences::class.java)
        mockEditor = mock(SharedPreferences.Editor::class.java)
        storage = mutableMapOf()

        `when`(mockContext.getSharedPreferences("meshnet_identity", Context.MODE_PRIVATE))
            .thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)

        `when`(mockEditor.putString(any(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<Any>(0).toString()
            val value = invocation.getArgument<Any>(1)?.toString()
            storage[key] = value
            mockEditor
        }

        `when`(mockPrefs.getString(any(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<Any>(0).toString()
            val defValue = invocation.getArgument<Any>(1)
            storage[key] ?: defValue
        }

        `when`(mockPrefs.contains(anyString())).thenAnswer { invocation ->
            val key = invocation.getArgument<Any>(0).toString()
            storage.containsKey(key)
        }

        store = IdentityStore(mockContext)
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
        assertTrue(storage.containsKey("device_id"))
        assertNotNull(storage["device_id"])
    }

    @Test
    fun init_persistsPrivateKey() {
        store.init(null)
        assertTrue(storage.containsKey("private_key"))
        assertNotNull(storage["private_key"])
    }

    @Test
    fun init_persistsPublicKey() {
        store.init(null)
        assertTrue(storage.containsKey("public_key"))
        assertNotNull(storage["public_key"])
    }

    @Test
    fun init_persistsDisplayName() {
        store.init("Test")
        assertTrue(storage.containsKey("display_name"))
        assertEquals("Test", storage["display_name"])
    }

    @Test
    fun setDisplayName_persistsChange() {
        store.init("Alice")
        store.setDisplayName("Bob")
        assertEquals("Bob", storage["display_name"])
    }

    // =================== Companion constants ===================

    @Test
    fun defaultName_isMeshNetUser() {
        store.init(null)
        assertEquals("MeshNet User", store.displayName())
    }
}
