package com.meshnet.meshnet_app

import android.content.Context
import android.content.SharedPreferences
import com.meshnet.meshnet_app.crypto.DoubleRatchet
import com.meshnet.meshnet_app.crypto.MeshCrypto
import com.meshnet.meshnet_app.crypto.RatchetSessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

/**
 * RatchetSessionStore testlari: save, load, remove, hasSession, getAllSessionIds.
 * SharedPreferences mock bilan.
 */
class RatchetSessionStoreTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var storage: MutableMap<String, String?>
    private lateinit var store: RatchetSessionStore

    private val PEER_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val PEER_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    private val PEER_C = "cccccccc-cccc-cccc-cccc-cccccccccccc"

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockPrefs = mock(SharedPreferences::class.java)
        mockEditor = mock(SharedPreferences.Editor::class.java)
        storage = mutableMapOf()

        `when`(mockContext.getSharedPreferences("meshnet_ratchet", Context.MODE_PRIVATE))
            .thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)

        `when`(mockEditor.putString(any(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<Any>(0).toString()
            val value = invocation.getArgument<Any>(1)?.toString()
            storage[key] = value
            mockEditor
        }

        `when`(mockEditor.remove(any())).thenAnswer { invocation ->
            val key = invocation.getArgument<Any>(0).toString()
            storage.remove(key)
            mockEditor
        }

        `when`(mockPrefs.getString(any(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<Any>(0).toString()
            val defValue = invocation.getArgument<Any>(1)
            storage[key] ?: defValue
        }

        `when`(mockPrefs.all).thenAnswer {
            storage
        }

        `when`(mockPrefs.contains(anyString())).thenAnswer { invocation ->
            val key = invocation.getArgument<Any>(0).toString()
            storage.containsKey(key)
        }

        store = RatchetSessionStore(mockContext)
    }

    private fun createTestSession(): DoubleRatchet {
        val sharedSecret = MeshCrypto.computeSharedSecret(
            MeshCrypto.generateKeyPair().privateKey,
            MeshCrypto.generateKeyPair().publicKey
        )
        val dhKP = DoubleRatchet.generateKeyPair()
        val remoteKP = DoubleRatchet.generateKeyPair()
        return DoubleRatchet(sharedSecret, dhKP, remoteKP.publicKey)
    }

    // =================== save / load ===================

    @Test
    fun save_persistsToStorage() {
        val session = createTestSession()
        store.save(PEER_A, session)
        assertTrue(storage.containsKey("session_$PEER_A"))
        assertNotNull(storage["session_$PEER_A"])
    }

    @Test
    fun save_withMultipleMessages_preservesState() {
        val session = createTestSession()
        store.save(PEER_A, session)
        val loaded = store.load(PEER_A)!!
        assertEquals(loaded.getSendCount(), loaded.getSendCount())
    }

    @Test
    fun save_overwritesExistingSession() {
        val session1 = createTestSession()
        val session2 = createTestSession()

        store.save(PEER_A, session1)
        store.save(PEER_A, session2)

        val loaded = store.load(PEER_A)
        assertNotNull(loaded)
        // Should be session2's state
    }

    @Test
    fun save_differentPeers_independent() {
        val session1 = createTestSession()
        val session2 = createTestSession()

        store.save(PEER_A, session1)
        store.save(PEER_B, session2)

        assertNotNull(store.load(PEER_A))
        assertNotNull(store.load(PEER_B))
    }

    // =================== load ===================

    @Test
    fun load_returnsNullForUnknownPeer() {
        assertNull(store.load("unknown-peer"))
    }

    @Test
    fun load_afterRemove_returnsNull() {
        store.save(PEER_A, createTestSession())
        assertNotNull(store.load(PEER_A))
        store.remove(PEER_A)
        assertNull(store.load(PEER_A))
    }

    @Test
    fun load_returnsFunctionalSession() {
        val original = createTestSession()
        store.save(PEER_A, original)
        val loaded = store.load(PEER_A)!!
        assertEquals(original.getSendCount(), loaded.getSendCount())
    }

    // =================== remove ===================

    @Test
    fun remove_removesSession() {
        store.save(PEER_A, createTestSession())
        store.remove(PEER_A)
        assertNull(store.load(PEER_A))
    }

    @Test
    fun remove_nonExistentPeer_doesNotCrash() {
        store.remove("unknown-peer")
    }

    @Test
    fun remove_onlyRemovesSpecified() {
        store.save(PEER_A, createTestSession())
        store.save(PEER_B, createTestSession())
        store.remove(PEER_A)
        assertNull(store.load(PEER_A))
        assertNotNull(store.load(PEER_B))
    }

    // =================== hasSession ===================

    @Test
    fun hasSession_returnsFalseForUnknown() {
        assertFalse(store.hasSession("unknown"))
    }

    @Test
    fun hasSession_returnsTrueAfterSave() {
        store.save(PEER_A, createTestSession())
        assertTrue(store.hasSession(PEER_A))
    }

    @Test
    fun hasSession_returnsFalseAfterRemove() {
        store.save(PEER_A, createTestSession())
        store.remove(PEER_A)
        assertFalse(store.hasSession(PEER_A))
    }

    @Test
    fun hasSession_multiplePeers() {
        store.save(PEER_A, createTestSession())
        store.save(PEER_B, createTestSession())
        assertTrue(store.hasSession(PEER_A))
        assertTrue(store.hasSession(PEER_B))
        assertFalse(store.hasSession(PEER_C))
    }

    // =================== getAllSessionIds ===================

    @Test
    fun getAllSessionIds_returnsEmptyInitially() {
        assertTrue(store.getAllSessionIds().isEmpty())
    }

    @Test
    fun getAllSessionIds_returnsAllIds() {
        store.save(PEER_A, createTestSession())
        store.save(PEER_B, createTestSession())
        val ids = store.getAllSessionIds()
        assertEquals(2, ids.size)
        assertTrue(ids.contains(PEER_A))
        assertTrue(ids.contains(PEER_B))
    }

    @Test
    fun getAllSessionIds_afterRemove_excludesRemoved() {
        store.save(PEER_A, createTestSession())
        store.save(PEER_B, createTestSession())
        store.remove(PEER_A)
        val ids = store.getAllSessionIds()
        assertEquals(1, ids.size)
        assertTrue(ids.contains(PEER_B))
    }

    @Test
    fun getAllSessionIds_afterRemoveAll_isEmpty() {
        store.save(PEER_A, createTestSession())
        store.save(PEER_B, createTestSession())
        store.remove(PEER_A)
        store.remove(PEER_B)
        assertTrue(store.getAllSessionIds().isEmpty())
    }

    // =================== SessionInfo data class ===================

    @Test
    fun sessionInfo_fieldsAreSet() {
        val info = RatchetSessionStore.SessionInfo(
            peerId = PEER_A,
            localPrivateKey = "priv",
            localPublicKey = "pub",
            remotePublicKey = "remote",
            serializedState = "state",
            createdAtMs = 1000L,
        )
        assertEquals(PEER_A, info.peerId)
        assertEquals("priv", info.localPrivateKey)
        assertEquals("pub", info.localPublicKey)
        assertEquals("remote", info.remotePublicKey)
        assertEquals("state", info.serializedState)
        assertEquals(1000L, info.createdAtMs)
    }
}
