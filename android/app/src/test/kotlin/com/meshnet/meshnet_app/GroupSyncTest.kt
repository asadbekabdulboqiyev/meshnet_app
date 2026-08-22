package com.meshnet.meshnet_app

import android.content.Context
import com.meshnet.meshnet_app.crypto.MeshCrypto
import com.meshnet.meshnet_app.protocol.GroupStore
import com.meshnet.meshnet_app.protocol.MeshFrame
import com.meshnet.meshnet_app.protocol.MessageType
import com.meshnet.meshnet_app.protocol.RoutingEngine
import com.meshnet.meshnet_app.storage.MeshDatabase
import com.meshnet.meshnet_app.storage.PeerStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Guruh sinxronizatsiyasi testlari: GROUP_CREATE tarqatish va qabul qilish.
 * Muammo: guruh faqat lokal yaratilar, boshqa qurilmalar ko'rmas edi.
 * Yechim: ta'rif a'zolarga juftlik kaliti bilan shifrlangan holda yuboriladi.
 */
class GroupSyncTest {

    companion object {
        private const val ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        private const val ID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        private const val ID_C = "cccccccc-cccc-cccc-cccc-cccccccccccc"
    }

    private class Harness(
        val engine: RoutingEngine,
        val peerStore: PeerStore,
        val emitted: MutableList<MeshFrame> = mutableListOf(),
        val groupsReceived: MutableList<Triple<String, String, Int>> = mutableListOf(),
        val groupMsgs: MutableList<Triple<String, String, String>> = mutableListOf(),
    ) : RoutingEngine.MessageListener {
        override fun onTextReceived(from: String, message: String, messageId: String) {}
        override fun onDeliveryReport(messageId: String, delivered: Boolean) {}
        override fun onPairResult(deviceId: String, success: Boolean) {}
        override fun onPeerFound(deviceId: String) {}
        override fun onOutboxChanged(messageId: String, status: String) {}
        override fun onFrameToSend(frame: MeshFrame, transport: String?) {
            emitted.add(frame)
        }
        override fun onRouteChanged(destination: String, nextHop: String, hopCount: Int, quality: Int) {}

        override fun onGroupReceived(groupId: String, name: String, memberCount: Int) {
            groupsReceived.add(Triple(groupId, name, memberCount))
        }

        override fun onGroupMessageReceived(
            groupId: String,
            senderId: String,
            message: String,
            senderName: String,
            messageId: String,
        ) {
            groupMsgs.add(Triple(groupId, senderId, message))
        }

        fun receivedGroupMessages() = groupMsgs
    }

    @Before
    fun setUp() {
        MeshDatabase.setInstance(TestDatabaseHelper.createMockDatabase())
    }

    @After
    fun tearDown() {
        MeshDatabase.resetInstance()
    }

    private fun makeEngine(id: String, keyPair: MeshCrypto.KeyPair): Harness {
        val ctx = mock(Context::class.java)
        val store = PeerStore(ctx)
        val engine = RoutingEngine(ctx, id, keyPair.privateKey, store)
        engine.setIdentityPublicKey(keyPair.publicKey)
        val harness = Harness(engine, store)
        engine.addListener(harness)
        return harness
    }

    private fun makeGroup(creatorId: String, vararg memberIds: String): GroupStore.Group {
        val members = mutableListOf(GroupStore.GroupMember(creatorId, "Creator", "admin"))
        memberIds.forEach { members.add(GroupStore.GroupMember(it, "Member-$it", "member")) }
        return GroupStore.Group(
            groupId = "grp-test-${creatorId.take(4)}",
            name = "Test Group",
            members = members,
            symmetricKey = MeshCrypto.b64(ByteArray(32) { 0x42 }),
            createdAtMs = System.currentTimeMillis(),
            createdBy = creatorId,
        )
    }

    // =================== distributeGroup ===================

    @Test
    fun distributeGroup_sendsFramePerAuthorizedMember() {
        val keyA = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))
        a.peerStore.markAuthorized(ID_C, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))

        val group = makeGroup(ID_A, ID_B, ID_C)
        val sent = a.engine.distributeGroup(group)

        assertEquals(2, sent)
        assertEquals(2, a.emitted.size)
        assertTrue(a.emitted.all { it.type == MessageType.GROUP_CREATE })
        assertTrue(a.emitted.any { it.targetId == ID_B })
        assertTrue(a.emitted.any { it.targetId == ID_C })
    }

    @Test
    fun distributeGroup_skipsUnauthorizedMembers() {
        val keyA = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        // Only B is paired; C is selected but not authorized.
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))

        val group = makeGroup(ID_A, ID_B, ID_C)
        val sent = a.engine.distributeGroup(group)

        assertEquals(1, sent)
        assertEquals(ID_B, a.emitted.single().targetId)
    }

    @Test
    fun distributeGroup_excludesSelfFromRecipients() {
        val keyA = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)

        val group = makeGroup(ID_A) // only self as member
        val sent = a.engine.distributeGroup(group)

        assertEquals(0, sent)
        assertTrue(a.emitted.isEmpty())
    }

    // =================== receive + upsert ===================

    @Test
    fun groupSync_roundTrip_memberStoresDefinitionAndIsNotified() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyB = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, keyB)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        b.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        val group = makeGroup(ID_A, ID_B)
        a.engine.distributeGroup(group)

        val frame = a.emitted.single()
        assertEquals(MessageType.GROUP_CREATE, frame.type)
        b.engine.handleIncomingFrame(frame)

        // B now knows the group with the exact same definition.
        val stored = b.engine.getStoredGroup(group.groupId)
        assertNotNull(stored)
        assertEquals(group.name, stored!!.name)
        assertEquals(group.symmetricKey, stored.symmetricKey)
        assertEquals(group.createdBy, stored.createdBy)
        assertEquals(group.members.size, stored.members.size)
        assertTrue(stored.members.any { it.deviceId == ID_B })

        // UI was notified.
        assertEquals(1, b.groupsReceived.size)
        assertEquals(group.groupId, b.groupsReceived.first().first)
        assertEquals(group.name, b.groupsReceived.first().second)
    }

    @Test
    fun groupSync_upsertIsIdempotent() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyB = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, keyB)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        b.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        val group = makeGroup(ID_A, ID_B)
        a.engine.distributeGroup(group)
        val frame = a.emitted.single()

        b.engine.handleIncomingFrame(frame)
        b.engine.handleIncomingFrame(frame) // duplicate flood copy

        val stored = b.engine.getStoredGroup(group.groupId)
        assertNotNull(stored)
        assertEquals(group.members.size, stored!!.members.size)
        assertEquals(1, b.groupsReceived.size)
    }

    @Test
    fun groupSync_updatedDefinitionReplacesMembership() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyB = MeshCrypto.generateKeyPair()
        val keyC = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, keyB)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        b.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        val groupV1 = makeGroup(ID_A, ID_B, ID_C)
        a.engine.distributeGroup(groupV1)
        b.engine.handleIncomingFrame(a.emitted.first())

        val groupV2 = makeGroup(ID_A, ID_B) // C removed
        a.engine.distributeGroup(groupV2)
        b.engine.handleIncomingFrame(a.emitted.last())

        val stored = b.engine.getStoredGroup(groupV1.groupId)
        assertNotNull(stored)
        assertFalse(stored!!.members.any { it.deviceId == ID_C })
        assertTrue(stored.members.any { it.deviceId == ID_B })
    }

    // =================== end-to-end messaging ===================

    @Test
    fun groupMessage_flowsAfterDefinitionSync() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyB = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, keyB)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        b.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        val group = makeGroup(ID_A, ID_B)

        // 1. Creator distributes the definition.
        a.engine.distributeGroup(group)
        b.engine.handleIncomingFrame(a.emitted.single())

        // 2. Creator stores the group locally and sends a chat message.
        a.engine.storeGroup(group)
        a.engine.sendGroupMessage(group.groupId, "salom jamoa")

        // 3. Member receives and decrypts it using the synced key.
        val msgFrames = a.emitted.filter { it.type == MessageType.GROUP_MSG }
        assertEquals(1, msgFrames.size)
        b.engine.handleIncomingFrame(msgFrames.single())

        val received = b.receivedGroupMessages()
        assertEquals(1, received.size)
        assertEquals("salom jamoa", received.first().third)
        assertEquals(group.groupId, received.first().first)
    }

    @Test
    fun groupMessage_undecryptableWithoutDefinition() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyB = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, keyB)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        b.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        // Craft a GROUP_MSG encrypted under a key nobody stored on B.
        // (Shared mock DB between engines would otherwise leak definitions.)
        val foreignKey = ByteArray(32) { 0x11 }
        val aad = "MeshGroup:grp-foreign".toByteArray(Charsets.UTF_8)
        val frame = MeshFrame(
            type = MessageType.GROUP_MSG,
            hopLimit = RoutingEngine.MAX_HOP,
            ttl = 6,
            encrypted = true,
            senderId = ID_A,
            targetId = ID_B,
            msgSeq = 1L,
            payload = MeshCrypto.encrypt(foreignKey, "secret".toByteArray(Charsets.UTF_8), aad),
            senderPublicKey = null,
        )

        b.engine.handleIncomingFrame(frame)
        assertEquals(0, b.receivedGroupMessages().size)
    }

    @Test
    fun groupSync_specialCharactersSurviveRoundtrip() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyB = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, keyB)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        b.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        val members = listOf(
            GroupStore.GroupMember(ID_A, "Al\\pha | beta\ngamma", "admin"),
            GroupStore.GroupMember(ID_B, "O'zbek | nomi", "member"),
        )
        val group = GroupStore.Group(
            groupId = "grp|weird\\id",
            name = "Guruh | test\ndir",
            members = members,
            symmetricKey = MeshCrypto.b64(ByteArray(32) { 0x42 }),
            createdAtMs = 1234567890L,
            createdBy = ID_A,
        )
        a.engine.distributeGroup(group)
        b.engine.handleIncomingFrame(a.emitted.single())

        val stored = b.engine.getStoredGroup(group.groupId)
        assertNotNull(stored)
        assertEquals(group.name, stored!!.name)
        assertEquals("Al\\pha | beta\ngamma", stored.members.first { it.deviceId == ID_A }.displayName)
        assertEquals("O'zbek | nomi", stored.members.first { it.deviceId == ID_B }.displayName)
    }

    // =================== group_messages persistence ===================

    @Test
    fun groupMessages_crudRoundtrip() {
        val db = MeshDatabase.getInstance(mock(Context::class.java))
        db.insertGroupMessage(MeshDatabase.GroupMessage(
            messageId = "m1", groupId = "g1", senderId = ID_A,
            senderName = "A", message = "hello", fromMe = false,
            timestampMs = 1000L, status = "delivered",
        ))
        db.insertGroupMessage(MeshDatabase.GroupMessage(
            messageId = "m2", groupId = "g1", senderId = ID_A,
            senderName = "A", message = "again", fromMe = true,
            timestampMs = 2000L, status = "pending",
        ))

        assertEquals(2, db.getGroupMessages("g1").size)

        val byId = db.getGroupMessageById("m1")
        assertNotNull(byId)
        assertEquals("hello", byId!!.message)
        assertFalse(byId.fromMe)

        // Dedup: same id ignored.
        db.insertGroupMessage(MeshDatabase.GroupMessage(
            messageId = "m1", groupId = "g1", senderId = ID_A,
            senderName = "A", message = "DUPLICATE", fromMe = false,
            timestampMs = 9999L, status = "delivered",
        ))
        assertEquals(2, db.getGroupMessages("g1").size)

        db.updateGroupMessageStatus("m2", "delivered")
        assertEquals("delivered", db.getGroupMessageById("m2")!!.status)

        assertNull(db.getGroupMessageById("nope"))

        db.deleteGroupMessages("g1")
        assertEquals(0, db.getGroupMessages("g1").size)
    }
}
