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
import java.util.UUID

class IdentityStoreStressTest {

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

    @Test
    fun init_idempotent_sameDeviceId() {
        store.init("Alice")
        val id1 = store.deviceId()
        store.init("Bob")
        val id2 = store.deviceId()
        assertEquals(id1, id2)
    }

    @Test
    fun init_idempotent_sameKeys() {
        store.init(null)
        val pk1 = store.privateKey()
        val pub1 = store.publicKey()
        store.init(null)
        assertTrue(pk1.contentEquals(store.privateKey()))
        assertTrue(pub1.contentEquals(store.publicKey()))
    }

    @Test
    fun init_idempotent_preservesDisplayName() {
        store.init("Alice")
        store.init("Bob")
        assertEquals("Bob", store.displayName())
    }

    @Test
    fun deviceId_alwaysReturnsSameValue() {
        store.init(null)
        val id1 = store.deviceId()
        val id2 = store.deviceId()
        val id3 = store.deviceId()
        assertEquals(id1, id2)
        assertEquals(id2, id3)
    }

    @Test
    fun deviceId_autoInitializesIfMissing() {
        val id = store.deviceId()
        assertNotNull(id)
        assertTrue(id.isNotEmpty())
        assertEquals(32, store.privateKey().size)
        assertEquals(32, store.publicKey().size)
    }

    @Test
    fun privateKey_consistentAcrossCalls() {
        store.init(null)
        val k1 = store.privateKey()
        val k2 = store.privateKey()
        val k3 = store.privateKey()
        assertTrue(k1.contentEquals(k2))
        assertTrue(k2.contentEquals(k3))
    }

    @Test
    fun publicKey_consistentAcrossCalls() {
        store.init(null)
        val k1 = store.publicKey()
        val k2 = store.publicKey()
        val k3 = store.publicKey()
        assertTrue(k1.contentEquals(k2))
        assertTrue(k2.contentEquals(k3))
    }

    @Test
    fun privateKeyAndPublicKey_different() {
        store.init(null)
        assertFalse(store.privateKey().contentEquals(store.publicKey()))
    }

    @Test
    fun keyPair_validECDH() {
        store.init(null)
        val otherKP = MeshCrypto.generateKeyPair()
        val shared1 = MeshCrypto.computeSharedSecret(store.privateKey(), otherKP.publicKey)
        val shared2 = MeshCrypto.computeSharedSecret(store.privateKey(), otherKP.publicKey)
        assertEquals(32, shared1.size)
        assertTrue(shared1.contentEquals(shared2))
    }

    @Test
    fun setDisplayName_multipleChanges() {
        store.init("Alice")
        store.setDisplayName("Bob")
        assertEquals("Bob", store.displayName())
        store.setDisplayName("Charlie")
        assertEquals("Charlie", store.displayName())
    }

    @Test
    fun setDisplayName_empty_usesDefault() {
        store.setDisplayName("")
        assertEquals("MeshNet User", store.displayName())
    }

    @Test
    fun setDisplayName_whitespace_usesDefault() {
        store.setDisplayName("   ")
        assertEquals("MeshNet User", store.displayName())
    }

    @Test
    fun setDisplayName_trimsSpaces() {
        store.setDisplayName("  Hello  ")
        assertEquals("Hello", store.displayName())
    }

    @Test
    fun displayName_defaultBeforeInit() {
        assertEquals("MeshNet User", store.displayName())
    }

    @Test
    fun displayName_afterInitWithNull_usesDefault() {
        store.init(null)
        assertEquals("MeshNet User", store.displayName())
    }

    @Test
    fun displayName_afterInitWithName_usesName() {
        store.init("Alice")
        assertEquals("Alice", store.displayName())
    }

    @Test
    fun concurrentInit_noException() {
        val threads = (1..10).map {
            Thread { store.init("User $it") }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(2000) }
        assertNotNull(store.deviceId())
    }

    @Test
    fun concurrentReadDeviceId_sameValue() {
        store.init(null)
        val results = mutableListOf<String>()
        val threads = (1..10).map {
            Thread {
                synchronized(results) {
                    results.add(store.deviceId())
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(2000) }
        assertEquals(10, results.size)
        assertTrue(results.all { it == results[0] })
    }

    @Test
    fun concurrentSetDisplayName_noException() {
        val threads = (1..10).map { i ->
            Thread { store.setDisplayName("Name $i") }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(2000) }
        assertNotNull(store.displayName())
    }

    @Test
    fun persistence_deviceId() {
        store.init(null)
        assertTrue(storage.containsKey("device_id"))
        assertNotNull(storage["device_id"])
    }

    @Test
    fun persistence_privateKey() {
        store.init(null)
        assertTrue(storage.containsKey("private_key"))
        assertNotNull(storage["private_key"])
    }

    @Test
    fun persistence_publicKey() {
        store.init(null)
        assertTrue(storage.containsKey("public_key"))
        assertNotNull(storage["public_key"])
    }

    @Test
    fun persistence_displayName() {
        store.init("Test")
        assertTrue(storage.containsKey("display_name"))
        assertEquals("Test", storage["display_name"])
    }

    @Test
    fun persistence_setDisplayName_updates() {
        store.init("Alice")
        store.setDisplayName("Bob")
        assertEquals("Bob", storage["display_name"])
    }

    @Test
    fun setDisplayName_preservesKeys() {
        store.init("Alice")
        val pk1 = store.privateKey()
        val pub1 = store.publicKey()
        store.setDisplayName("Bob")
        assertTrue(pk1.contentEquals(store.privateKey()))
        assertTrue(pub1.contentEquals(store.publicKey()))
    }

    @Test
    fun setDisplayName_preservesDeviceId() {
        store.init("Alice")
        val id = store.deviceId()
        store.setDisplayName("Bob")
        assertEquals(id, store.deviceId())
    }

    @Test
    fun deviceId_validUUIDFormat() {
        store.init(null)
        val id = store.deviceId()
        val uuid = UUID.fromString(id)
        assertNotNull(uuid)
    }

    @Test
    fun deviceId_5PartsSeparatedByDash() {
        store.init(null)
        val parts = store.deviceId().split("-")
        assertEquals(5, parts.size)
    }

    @Test
    fun setDisplayName_specialChars_preserved() {
        store.setDisplayName("User & <Name>")
        assertEquals("User & <Name>", store.displayName())
    }

    @Test
    fun setDisplayName_unicode_preserved() {
        store.setDisplayName("Yangi foydalanuvchi")
        assertEquals("Yangi foydalanuvchi", store.displayName())
    }

    @Test
    fun setDisplayName_longName_preserved() {
        val name = "X".repeat(1000)
        store.setDisplayName(name)
        assertEquals(name, store.displayName())
    }
}
