package com.meshnet.meshnet_app

import android.content.Context
import com.meshnet.meshnet_app.protocol.GroupStore
import com.meshnet.meshnet_app.crypto.MeshCrypto
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

class GroupMessagingTest {

    private lateinit var mockContext: Context
    private lateinit var groupStore: GroupStore
    private lateinit var peerStore: PeerStore

    private val CREATOR_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val MEMBER_1 = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    private val MEMBER_2 = "cccccccc-cccc-cccc-cccc-cccccccccccc"
    private val MEMBER_3 = "dddddddd-dddd-dddd-dddd-dddddddddddd"

    @Before
    fun setUp() {
        MeshDatabase.setInstance(TestDatabaseHelper.createMockDatabase())
        mockContext = mock(Context::class.java)
        groupStore = GroupStore(mockContext)
        peerStore = PeerStore(mockContext)
    }

    @After
    fun tearDown() {
        MeshDatabase.resetInstance()
    }

    @Test
    fun createGroup_returnsValidGroupId() {
        val group = groupStore.createGroup("Test", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        assertNotNull(group.groupId)
        assertTrue(group.groupId.isNotEmpty())
    }

    @Test
    fun createGroup_setsCreatorAsAdmin() {
        val group = groupStore.createGroup("Test", listOf(GroupStore.GroupMember(CREATOR_ID, "Admin", "admin")), CREATOR_ID)
        assertEquals("admin", group.members.first { it.deviceId == CREATOR_ID }.role)
    }

    @Test
    fun createGroup_symmetricKeyIs32Bytes() {
        val group = groupStore.createGroup("Test", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        val key = MeshCrypto.unb64(group.symmetricKey)
        assertEquals(32, key.size)
    }

    @Test
    fun createGroup_createdByMatchesCreator() {
        val group = groupStore.createGroup("Test", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        assertEquals(CREATOR_ID, group.createdBy)
    }

    @Test
    fun createGroup_createdAtIsRecent() {
        val before = System.currentTimeMillis()
        val group = groupStore.createGroup("Test", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        val after = System.currentTimeMillis()
        assertTrue(group.createdAtMs >= before)
        assertTrue(group.createdAtMs <= after)
    }

    @Test
    fun addMember_increasesMemberCount() {
        val group = groupStore.createGroup("Test", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        groupStore.addMember(group.groupId, GroupStore.GroupMember(MEMBER_1, "B", "member"))
        val updated = groupStore.getGroup(group.groupId)!!
        assertEquals(2, updated.members.size)
    }

    @Test
    fun addMember_setsCorrectRole() {
        val group = groupStore.createGroup("Test", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        groupStore.addMember(group.groupId, GroupStore.GroupMember(MEMBER_1, "B", "moderator"))
        val updated = groupStore.getGroup(group.groupId)!!
        assertEquals("moderator", updated.members.first { it.deviceId == MEMBER_1 }.role)
    }

    @Test
    fun addMember_duplicateIgnored() {
        val group = groupStore.createGroup("Test", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        val member = GroupStore.GroupMember(MEMBER_1, "B", "member")
        groupStore.addMember(group.groupId, member)
        groupStore.addMember(group.groupId, member)
        groupStore.addMember(group.groupId, member)
        val updated = groupStore.getGroup(group.groupId)!!
        assertEquals(2, updated.members.size)
    }

    @Test
    fun removeMember_decreasesMemberCount() {
        val group = groupStore.createGroup("Test", listOf(
            GroupStore.GroupMember(CREATOR_ID, "A", "admin"),
            GroupStore.GroupMember(MEMBER_1, "B", "member"),
        ), CREATOR_ID)
        groupStore.removeMember(group.groupId, MEMBER_1)
        val updated = groupStore.getGroup(group.groupId)!!
        assertEquals(1, updated.members.size)
    }

    @Test
    fun removeMember_creatorCanBeRemoved() {
        val group = groupStore.createGroup("Test", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        groupStore.addMember(group.groupId, GroupStore.GroupMember(MEMBER_1, "B", "member"))
        groupStore.removeMember(group.groupId, CREATOR_ID)
        val updated = groupStore.getGroup(group.groupId)!!
        assertEquals(1, updated.members.size)
    }

    @Test
    fun removeMember_multipleMembersIndependent() {
        val group = groupStore.createGroup("Test", listOf(
            GroupStore.GroupMember(CREATOR_ID, "A", "admin"),
            GroupStore.GroupMember(MEMBER_1, "B", "member"),
            GroupStore.GroupMember(MEMBER_2, "C", "member"),
        ), CREATOR_ID)
        groupStore.removeMember(group.groupId, MEMBER_1)
        val updated = groupStore.getGroup(group.groupId)!!
        assertEquals(2, updated.members.size)
        assertTrue(updated.members.any { it.deviceId == MEMBER_2 })
        assertFalse(updated.members.any { it.deviceId == MEMBER_1 })
    }

    @Test
    fun deleteGroup_removesFromAllGroups() {
        val group = groupStore.createGroup("Test", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        groupStore.deleteGroup(group.groupId)
        assertTrue(groupStore.getAllGroups().isEmpty())
    }

    @Test
    fun deleteGroup_getGroupReturnsNull() {
        val group = groupStore.createGroup("Test", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        groupStore.deleteGroup(group.groupId)
        assertNull(groupStore.getGroup(group.groupId))
    }

    @Test
    fun deleteGroup_doesNotAffectOtherGroups() {
        val g1 = groupStore.createGroup("G1", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        val g2 = groupStore.createGroup("G2", listOf(GroupStore.GroupMember(MEMBER_1, "B", "admin")), MEMBER_1)
        groupStore.deleteGroup(g1.groupId)
        assertNotNull(groupStore.getGroup(g2.groupId))
    }

    @Test
    fun rotateKey_generatesNewKey() {
        val group = groupStore.createGroup("Test", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        val oldKey = groupStore.getSymmetricKey(group.groupId)
        val newKey = groupStore.rotateKey(group.groupId)
        assertFalse(oldKey!!.contentEquals(newKey!!))
    }

    @Test
    fun rotateKey_oldKeyNoLongerWorks() {
        val group = groupStore.createGroup("Test", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        val oldKey = groupStore.getSymmetricKey(group.groupId)
        groupStore.rotateKey(group.groupId)
        val currentKey = groupStore.getSymmetricKey(group.groupId)
        assertFalse(oldKey!!.contentEquals(currentKey!!))
    }

    @Test
    fun rotateKey_unknownGroupReturnsNull() {
        assertNull(groupStore.rotateKey("nonexistent"))
    }

    @Test
    fun getSymmetricKey_multipleGroupsHaveDifferentKeys() {
        val g1 = groupStore.createGroup("G1", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        val g2 = groupStore.createGroup("G2", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        val k1 = groupStore.getSymmetricKey(g1.groupId)
        val k2 = groupStore.getSymmetricKey(g2.groupId)
        assertFalse(k1!!.contentEquals(k2!!))
    }

    @Test
    fun getMemberDeviceIds_includesAllMembers() {
        val group = groupStore.createGroup("Test", listOf(
            GroupStore.GroupMember(CREATOR_ID, "A", "admin"),
            GroupStore.GroupMember(MEMBER_1, "B", "member"),
        ), CREATOR_ID)
        val ids = groupStore.getMemberDeviceIds(group.groupId)
        assertEquals(2, ids.size)
        assertTrue(ids.contains(CREATOR_ID))
        assertTrue(ids.contains(MEMBER_1))
    }

    @Test
    fun getMemberDeviceIds_afterRemoveExcludesRemoved() {
        val group = groupStore.createGroup("Test", listOf(
            GroupStore.GroupMember(CREATOR_ID, "A", "admin"),
            GroupStore.GroupMember(MEMBER_1, "B", "member"),
        ), CREATOR_ID)
        groupStore.removeMember(group.groupId, MEMBER_1)
        val ids = groupStore.getMemberDeviceIds(group.groupId)
        assertEquals(1, ids.size)
        assertFalse(ids.contains(MEMBER_1))
    }

    @Test
    fun getAllGroups_returnsCorrectCount() {
        for (i in 1..5) {
            groupStore.createGroup("Group $i", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        }
        assertEquals(5, groupStore.getAllGroups().size)
    }

    @Test
    fun updateGroup_changesName() {
        val group = groupStore.createGroup("Old", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        groupStore.updateGroup(group.copy(name = "New"))
        assertEquals("New", groupStore.getGroup(group.groupId)!!.name)
    }

    @Test
    fun updateGroup_preservesMembers() {
        val group = groupStore.createGroup("Test", listOf(
            GroupStore.GroupMember(CREATOR_ID, "A", "admin"),
            GroupStore.GroupMember(MEMBER_1, "B", "member"),
        ), CREATOR_ID)
        groupStore.updateGroup(group.copy(name = "Updated"))
        val updated = groupStore.getGroup(group.groupId)!!
        assertEquals(2, updated.members.size)
    }

    @Test
    fun groupMember_defaultDisplayName() {
        val group = groupStore.createGroup("Test", listOf(GroupStore.GroupMember(MEMBER_1, "User")), CREATOR_ID)
        assertEquals("member", group.members[0].role)
    }

    @Test
    fun multipleMembers_allHaveUniqueIds() {
        val group = groupStore.createGroup("Test", listOf(
            GroupStore.GroupMember(CREATOR_ID, "A", "admin"),
            GroupStore.GroupMember(MEMBER_1, "B", "member"),
            GroupStore.GroupMember(MEMBER_2, "C", "member"),
            GroupStore.GroupMember(MEMBER_3, "D", "member"),
        ), CREATOR_ID)
        val ids = groupStore.getMemberDeviceIds(group.groupId).toSet()
        assertEquals(4, ids.size)
    }

    @Test
    fun peerStore_markAuthorized_forGroupMember() {
        peerStore.markAuthorized(MEMBER_1, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))
        val peer = peerStore.authorized(MEMBER_1)
        assertNotNull(peer)
        assertTrue(peer!!.authorized)
    }

    @Test
    fun peerStore_removeGroupMember() {
        peerStore.markAuthorized(MEMBER_1, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))
        peerStore.remove(MEMBER_1)
        assertNull(peerStore.authorized(MEMBER_1))
    }

    @Test
    fun groupStore_emptyName_works() {
        val group = groupStore.createGroup("", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        assertEquals("", group.name)
    }

    @Test
    fun groupStore_longName_works() {
        val longName = "A".repeat(500)
        val group = groupStore.createGroup(longName, listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        assertEquals(longName, group.name)
    }

    @Test
    fun addMember_thenRemoveMember_backToOriginal() {
        val group = groupStore.createGroup("Test", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        groupStore.addMember(group.groupId, GroupStore.GroupMember(MEMBER_1, "B", "member"))
        groupStore.removeMember(group.groupId, MEMBER_1)
        val updated = groupStore.getGroup(group.groupId)!!
        assertEquals(1, updated.members.size)
        assertEquals(CREATOR_ID, updated.members[0].deviceId)
    }

    @Test
    fun createGroup_multipleMembers_allPresent() {
        val members = listOf(
            GroupStore.GroupMember(CREATOR_ID, "A", "admin"),
            GroupStore.GroupMember(MEMBER_1, "B", "member"),
            GroupStore.GroupMember(MEMBER_2, "C", "member"),
        )
        val group = groupStore.createGroup("Test", members, CREATOR_ID)
        assertEquals(3, group.members.size)
    }

    @Test
    fun getAllGroups_afterDeleteAll_isEmpty() {
        val g1 = groupStore.createGroup("G1", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        val g2 = groupStore.createGroup("G2", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        groupStore.deleteGroup(g1.groupId)
        groupStore.deleteGroup(g2.groupId)
        assertTrue(groupStore.getAllGroups().isEmpty())
    }
}
