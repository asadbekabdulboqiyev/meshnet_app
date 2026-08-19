package com.meshnet.meshnet_app

import android.content.Context
import android.content.SharedPreferences
import com.meshnet.meshnet_app.crypto.MeshCrypto
import com.meshnet.meshnet_app.storage.PeerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class PeerStoreStressTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var storage: MutableMap<String, String?>
    private lateinit var store: PeerStore

    private val ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val ID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    private val ID_C = "cccccccc-cccc-cccc-cccc-cccccccccccc"

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

    @Test
    fun upsert_50Peers_allStored() {
        for (i in 1..50) {
            store.upsert(PeerStore.Peer("id-$i", "Peer $i", "pk-$i", false))
        }
        assertEquals(50, store.all().size)
    }

    @Test
    fun upsert_overwriteSameId_onlyOnePeer() {
        store.upsert(PeerStore.Peer(ID_A, "Alice1", "pk1", false))
        store.upsert(PeerStore.Peer(ID_A, "Alice2", "pk2", false))
        store.upsert(PeerStore.Peer(ID_A, "Alice3", "pk3", false))
        assertEquals(1, store.all().size)
        assertEquals("Alice3", store.get(ID_A)!!.displayName)
    }

    @Test
    fun concurrentUpsert_noException() {
        val threads = (1..20).map { i ->
            Thread {
                store.upsert(PeerStore.Peer("id-$i", "Peer $i", "pk-$i", false))
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(2000) }
    }

    @Test
    fun concurrentMarkSeen_noException() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", "pk", false))
        val threads = (1..20).map {
            Thread {
                store.markSeen(ID_A)
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(2000) }
        assertNotNull(store.get(ID_A))
    }

    @Test
    fun markSeen_unknownPeer_noNewPeerCreated() {
        store.markSeen("unknown-id")
        assertNull(store.get("unknown-id"))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun authorized_onlyAuthorizedReturned() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", "pkA", false))
        store.markAuthorized(ID_B, "pkB")
        assertNull(store.authorized(ID_A))
        assertNotNull(store.authorized(ID_B))
    }

    @Test
    fun clear_removeAllPeers() {
        for (i in 1..10) {
            store.upsert(PeerStore.Peer("id-$i", "Peer $i", "pk-$i", false))
        }
        for (i in 1..10) {
            store.remove("id-$i")
        }
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun persistence_simulation() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", "pkA", true))
        store.upsert(PeerStore.Peer(ID_B, "Bob", "pkB", false))
        assertTrue(storage.containsKey("peers_json"))
        assertNotNull(storage["peers_json"])
    }

    @Test
    fun all_sortedByLastSeen() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", "pkA", false))
        Thread.sleep(10)
        store.upsert(PeerStore.Peer(ID_B, "Bob", "pkB", false))
        Thread.sleep(10)
        store.upsert(PeerStore.Peer(ID_C, "Charlie", "pkC", false))
        val all = store.all()
        assertEquals(ID_C, all[0].deviceId)
        assertEquals(ID_B, all[1].deviceId)
        assertEquals(ID_A, all[2].deviceId)
    }

    @Test
    fun remove_multiplePeers_allRemoved() {
        for (i in 1..5) {
            store.upsert(PeerStore.Peer("id-$i", "Peer $i", "pk-$i", false))
        }
        for (i in 1..5) {
            store.remove("id-$i")
        }
        assertEquals(0, store.all().size)
    }

    @Test
    fun upsert_preservesTransport() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", "pkA", false, transport = "wifi"))
        assertEquals("wifi", store.get(ID_A)!!.transport)
    }

    @Test
    fun upsert_preservesRssi() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", "pkA", false, rssi = -75))
        assertEquals(-75, store.get(ID_A)!!.rssi)
    }

    @Test
    fun upsert_preservesLinkQuality() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", "pkA", false, linkQuality = 90))
        assertEquals(90, store.get(ID_A)!!.linkQuality)
    }

    @Test
    fun upsert_preservesHopDistance() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", "pkA", false, hopDistance = 3))
        assertEquals(3, store.get(ID_A)!!.hopDistance)
    }

    @Test
    fun markAuthorized_createsDefaultPeerIfMissing() {
        store.markAuthorized(ID_A, "pkA")
        val peer = store.get(ID_A)!!
        assertEquals("Peer", peer.displayName)
        assertEquals("pkA", peer.publicKey)
        assertTrue(peer.authorized)
    }

    @Test
    fun markAuthorized_updatesPublicKey() {
        store.markAuthorized(ID_A, "old-pk")
        store.markAuthorized(ID_A, "new-pk")
        assertEquals("new-pk", store.get(ID_A)!!.publicKey)
    }

    @Test
    fun markAuthorized_setsLastSeenNonZero() {
        store.markAuthorized(ID_A, "pkA")
        assertTrue(store.get(ID_A)!!.lastSeenMs > 0)
    }

    @Test
    fun peerDataClass_equality() {
        val p1 = PeerStore.Peer(ID_A, "Alice", "pk", true)
        val p2 = PeerStore.Peer(ID_A, "Alice", "pk", true)
        assertEquals(p1, p2)
    }

    @Test
    fun peerDataClass_toStringContainsId() {
        val peer = PeerStore.Peer(ID_A, "Alice", "pk", false)
        assertTrue(peer.toString().contains(ID_A))
    }

    @Test
    fun emptyIdPeer_canBeStored() {
        store.upsert(PeerStore.Peer("", "Empty", "", false))
        assertNotNull(store.get(""))
    }

    @Test
    fun longDisplayName_stored() {
        val name = "N".repeat(5000)
        store.upsert(PeerStore.Peer(ID_A, name, "pk", false))
        assertEquals(name, store.get(ID_A)!!.displayName)
    }

    @Test
    fun markSeen_after5Seconds_writesToStorage() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", "pk", false))
        Thread.sleep(5100)
        store.markSeen(ID_A)
        assertNotNull(store.get(ID_A))
    }

    @Test
    fun markSeen_within5Seconds_doesNotWrite() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", "pk", false))
        store.markSeen(ID_A)
        store.markSeen(ID_A)
        assertNotNull(store.get(ID_A))
    }

    @Test
    fun remove_thenUpsert_sameId_works() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", "pkA", false))
        store.remove(ID_A)
        assertNull(store.get(ID_A))
        store.upsert(PeerStore.Peer(ID_A, "Alice New", "pkA2", false))
        assertNotNull(store.get(ID_A))
        assertEquals("Alice New", store.get(ID_A)!!.displayName)
    }

    @Test
    fun parallelAuth_multipleThreads() {
        val threads = (1..10).map { i ->
            Thread {
                store.markAuthorized("peer-$i", "pk-$i")
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(2000) }
        for (i in 1..10) {
            assertNotNull(store.authorized("peer-$i"))
        }
    }

    @Test
    fun all_afterRemoveAll_isEmpty() {
        for (i in 1..20) {
            store.upsert(PeerStore.Peer("id-$i", "P$i", "pk-$i", false))
        }
        for (i in 1..20) {
            store.remove("id-$i")
        }
        assertEquals(0, store.all().size)
    }

    @Test
    fun upsert_doesNotAffectOtherPeers() {
        store.upsert(PeerStore.Peer(ID_A, "Alice", "pkA", false))
        store.upsert(PeerStore.Peer(ID_B, "Bob", "pkB", false))
        store.upsert(PeerStore.Peer(ID_A, "Alice Updated", "pkA2", false))
        assertEquals("Alice Updated", store.get(ID_A)!!.displayName)
        assertEquals("Bob", store.get(ID_B)!!.displayName)
    }

    @Test
    fun remove_nonExistent_noException() {
        store.remove("no-such-id")
        store.remove("another-no-such")
    }

    @Test
    fun get_afterMultipleOperations_correct() {
        store.upsert(PeerStore.Peer(ID_A, "A", "pk", false))
        store.upsert(PeerStore.Peer(ID_B, "B", "pk", false))
        store.remove(ID_A)
        store.upsert(PeerStore.Peer(ID_C, "C", "pk", false))
        assertNull(store.get(ID_A))
        assertNotNull(store.get(ID_B))
        assertNotNull(store.get(ID_C))
    }
}
