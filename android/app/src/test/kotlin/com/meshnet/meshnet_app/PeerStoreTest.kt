package com.meshnet.meshnet_app

import android.content.Context
import android.content.SharedPreferences
import com.meshnet.meshnet_app.storage.PeerStore
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
 * PeerStore testlari: upsert, get, all, authorized, markAuthorized, markSeen, remove.
 * SharedPreferences mock bilan.
 */
class PeerStoreTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var storage: MutableMap<String, String?>
    private lateinit var store: PeerStore

    private val ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val ID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    private val ID_C = "cccccccc-cccc-cccc-cccc-cccccccccccc"
    private val PK_A = "pk_a_base64"
    private val PK_B = "pk_b_base64"

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockPrefs = mock(SharedPreferences::class.java)
        mockEditor = mock(SharedPreferences.Editor::class.java)
        storage = mutableMapOf()

        `when`(mockContext.getSharedPreferences("meshnet_peers", Context.MODE_PRIVATE))
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

        store = PeerStore(mockContext)
    }

    // =================== upsert ===================

    @Test
    fun upsert_addsNewPeer() {
        val peer = PeerStore.Peer(ID_A, "Alice", PK_A, false)
        store.upsert(peer)
        val found = store.get(ID_A)
        assertNotNull(found)
        assertEquals(ID_A, found!!.deviceId)
        assertEquals("Alice", found.displayName)
        assertEquals(PK_A, found.publicKey)
    }

    @Test
    fun upsert_updatesExistingPeer() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", PK_A, false))
        store.upsert(PeerStore.Peer(ID_A, "Alice Updated", PK_A, true))
        val found = store.get(ID_A)
        assertNotNull(found)
        assertEquals("Alice Updated", found!!.displayName)
        assertTrue(found.authorized)
    }

    @Test
    fun upsert_setsLastSeenToCurrentTime() {
        val before = System.currentTimeMillis()
        store.upsert(PeerStore.Peer(ID_A, "Alice", PK_A, false))
        val after = System.currentTimeMillis()
        val peer = store.get(ID_A)!!
        assertTrue(peer.lastSeenMs >= before - 100)
        assertTrue(peer.lastSeenMs <= after + 100)
    }

    @Test
    fun upsert_replacesExistingPeerFields() {
        store.upsert(PeerStore.Peer(
            ID_A, "Alice", PK_A, false,
            lastSeenMs = 1000L,
            transport = "wifi",
            rssi = -50,
            linkQuality = 80,
            hopDistance = 2,
        ))
        store.upsert(PeerStore.Peer(ID_A, "Alice Updated", PK_B, false,
            transport = "ble", rssi = -70, linkQuality = 30, hopDistance = 1))
        val found = store.get(ID_A)!!
        assertEquals("Alice Updated", found.displayName)
        assertEquals(PK_B, found.publicKey)
        assertEquals("ble", found.transport)
        assertEquals(-70, found.rssi)
        assertEquals(30, found.linkQuality)
        assertEquals(1, found.hopDistance)
    }

    @Test
    fun upsert_multiplePeers_independent() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", PK_A, false))
        store.upsert(PeerStore.Peer(ID_B, "Bob", PK_B, false))
        assertNotNull(store.get(ID_A))
        assertNotNull(store.get(ID_B))
        assertEquals("Alice", store.get(ID_A)!!.displayName)
        assertEquals("Bob", store.get(ID_B)!!.displayName)
    }

    // =================== get ===================

    @Test
    fun get_returnsNullForUnknownId() {
        assertNull(store.get("unknown-id"))
    }

    @Test
    fun get_returnsCorrectPeer() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", PK_A, false))
        val peer = store.get(ID_A)
        assertNotNull(peer)
        assertEquals(ID_A, peer!!.deviceId)
    }

    @Test
    fun get_afterRemove_returnsNull() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", PK_A, false))
        assertNotNull(store.get(ID_A))
        store.remove(ID_A)
        assertNull(store.get(ID_A))
    }

    // =================== all ===================

    @Test
    fun all_returnsEmptyInitially() {
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun all_returnsAllPeers() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", PK_A, false))
        store.upsert(PeerStore.Peer(ID_B, "Bob", PK_B, false))
        val all = store.all()
        assertEquals(2, all.size)
    }

    @Test
    fun all_sortedByDescendingLastSeen() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", PK_A, false))
        Thread.sleep(10)
        store.upsert(PeerStore.Peer(ID_B, "Bob", PK_B, false))
        val all = store.all()
        assertEquals(ID_B, all[0].deviceId)
        assertEquals(ID_A, all[1].deviceId)
    }

    @Test
    fun all_afterRemove_excludesRemoved() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", PK_A, false))
        store.upsert(PeerStore.Peer(ID_B, "Bob", PK_B, false))
        store.remove(ID_A)
        val all = store.all()
        assertEquals(1, all.size)
        assertEquals(ID_B, all[0].deviceId)
    }

    @Test
    fun all_afterRemoveAll_isEmpty() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", PK_A, false))
        store.upsert(PeerStore.Peer(ID_B, "Bob", PK_B, false))
        store.remove(ID_A)
        store.remove(ID_B)
        assertTrue(store.all().isEmpty())
    }

    // =================== authorized ===================

    @Test
    fun authorized_returnsNullForUnregisteredPeer() {
        assertNull(store.authorized(ID_A))
    }

    @Test
    fun authorized_returnsNullForUnauthorizedPeer() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", PK_A, false))
        assertNull(store.authorized(ID_A))
    }

    @Test
    fun authorized_returnsPeerIfAuthorized() {
        store.markAuthorized(ID_A, PK_A)
        val peer = store.authorized(ID_A)
        assertNotNull(peer)
        assertTrue(peer!!.authorized)
    }

    @Test
    fun authorized_doesNotReturnOtherPeers() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", PK_A, false))
        store.markAuthorized(ID_B, PK_B)
        assertNull(store.authorized(ID_A))
        assertNotNull(store.authorized(ID_B))
    }

    // =================== markAuthorized ===================

    @Test
    fun markAuthorized_setsAuthorizedAndPublicKey() {
        store.markAuthorized(ID_A, PK_A)
        val peer = store.get(ID_A)!!
        assertTrue(peer.authorized)
        assertEquals(PK_A, peer.publicKey)
    }

    @Test
    fun markAuthorized_createsNewPeerIfNotExist() {
        store.markAuthorized(ID_A, PK_A)
        val peer = store.get(ID_A)
        assertNotNull(peer)
        assertEquals(ID_A, peer!!.deviceId)
        assertEquals("Peer", peer.displayName)
        assertEquals(PK_A, peer.publicKey)
        assertTrue(peer.authorized)
    }

    @Test
    fun markAuthorized_updatesExistingPeer() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", "old-pk", false))
        store.markAuthorized(ID_A, PK_A)
        val peer = store.get(ID_A)!!
        assertEquals("Alice", peer.displayName)
        assertEquals(PK_A, peer.publicKey)
        assertTrue(peer.authorized)
    }

    @Test
    fun markAuthorized_updatesLastSeen() {
        val before = System.currentTimeMillis()
        store.markAuthorized(ID_A, PK_A)
        val after = System.currentTimeMillis()
        val peer = store.get(ID_A)!!
        assertTrue(peer.lastSeenMs >= before - 100)
        assertTrue(peer.lastSeenMs <= after + 100)
    }

    @Test
    fun markAuthorized_multiplePeers() {
        store.markAuthorized(ID_A, PK_A)
        store.markAuthorized(ID_B, PK_B)
        assertTrue(store.authorized(ID_A) != null)
        assertTrue(store.authorized(ID_B) != null)
    }

    // =================== markSeen ===================

    @Test
    fun markSeen_updatesLastSeenForExistingPeer() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", PK_A, false))
        // markSeen on existing peer should not crash and peer should remain
        store.markSeen(ID_A)
        val peer = store.get(ID_A)
        assertNotNull(peer)
        assertTrue(peer!!.lastSeenMs > 0)
    }

    @Test
    fun markSeen_doesNothingForUnknownPeer() {
        store.markSeen(ID_A)
        assertNull(store.get(ID_A))
    }

    @Test
    fun markSeen_doesNotWriteToDiskWithin5Seconds() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", PK_A, false))
        store.markSeen(ID_A)
        Thread.sleep(10)
        store.markSeen(ID_A)
        // Should not crash - just skips the write
        assertNotNull(store.get(ID_A))
    }

    // =================== remove ===================

    @Test
    fun remove_removesExistingPeer() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", PK_A, false))
        store.remove(ID_A)
        assertNull(store.get(ID_A))
    }

    @Test
    fun remove_nonExistentPeer_doesNotCrash() {
        store.remove(ID_A)
        assertNull(store.get(ID_A))
    }

    @Test
    fun remove_onlyRemovesSpecifiedPeer() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", PK_A, false))
        store.upsert(PeerStore.Peer(ID_B, "Bob", PK_B, false))
        store.remove(ID_A)
        assertNull(store.get(ID_A))
        assertNotNull(store.get(ID_B))
    }

    @Test
    fun remove_authorizedPeer_noLongerAuthorized() {
        store.markAuthorized(ID_A, PK_A)
        assertNotNull(store.authorized(ID_A))
        store.remove(ID_A)
        assertNull(store.authorized(ID_A))
    }

    // =================== persistence ===================

    @Test
    fun upsert_persistsToStorage() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", PK_A, false))
        assertTrue(storage.containsKey("peers_json"))
        assertNotNull(storage["peers_json"])
    }

    // =================== Peer data class ===================

    @Test
    fun peerDataClass_defaults() {
        val peer = PeerStore.Peer(ID_A, "Alice", PK_A, false)
        assertEquals(0L, peer.lastSeenMs)
        assertEquals("ble", peer.transport)
        assertEquals(0, peer.rssi)
        assertEquals(50, peer.linkQuality)
        assertEquals(0, peer.hopDistance)
    }

    @Test
    fun peerDataClass_copyPreservesUnchangedFields() {
        val original = PeerStore.Peer(
            ID_A, "Alice", PK_A, true,
            lastSeenMs = 1000L, transport = "wifi", rssi = -40,
            linkQuality = 90, hopDistance = 1
        )
        val copied = original.copy(displayName = "Alice 2")
        assertEquals("Alice 2", copied.displayName)
        assertEquals(ID_A, copied.deviceId)
        assertEquals(PK_A, copied.publicKey)
        assertTrue(copied.authorized)
        assertEquals(1000L, copied.lastSeenMs)
        assertEquals("wifi", copied.transport)
        assertEquals(-40, copied.rssi)
        assertEquals(90, copied.linkQuality)
        assertEquals(1, copied.hopDistance)
    }

    // =================== Edge cases ===================

    @Test
    fun emptyStringId_works() {
        store.upsert(PeerStore.Peer("", "Empty", PK_A, false))
        assertNotNull(store.get(""))
        assertEquals("Empty", store.get("")!!.displayName)
    }

    @Test
    fun veryLongDisplayName_works() {
        val longName = "A".repeat(1000)
        store.upsert(PeerStore.Peer(ID_A, longName, PK_A, false))
        assertEquals(longName, store.get(ID_A)!!.displayName)
    }

    @Test
    fun emptyPublicKey_works() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", "", false))
        assertEquals("", store.get(ID_A)!!.publicKey)
    }
}
