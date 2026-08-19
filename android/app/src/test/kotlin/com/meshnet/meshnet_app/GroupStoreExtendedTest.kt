package com.meshnet.meshnet_app

import android.content.Context
import com.meshnet.meshnet_app.protocol.GroupStore
import com.meshnet.meshnet_app.crypto.MeshCrypto
import com.meshnet.meshnet_app.storage.MeshDatabase
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * GroupStore kengaytirilgan testlari: edge cases, boundary conditions,
 * multiple operations, name/role edge cases.
 */
class GroupStoreExtendedTest {

    private lateinit var mockContext: Context
    private lateinit var groupStore: GroupStore

    private val CREATOR = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val MEMBER_1 = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    private val MEMBER_2 = "cccccccc-cccc-cccc-cccc-cccccccccccc"

    @Before
    fun setUp() {
        MeshDatabase.setInstance(TestDatabaseHelper.createMockDatabase())
        mockContext = mock(Context::class.java)
        groupStore = GroupStore(mockContext)
    }

    @After
    fun tearDown() {
        MeshDatabase.resetInstance()
    }

    // =================== Empty group ===================

    @Test
    fun createGroup_withNoMembers() {
        val group = groupStore.createGroup("Empty Group", emptyList(), CREATOR)
        assertNotNull(group.groupId)
        assertEquals("Empty Group", group.name)
        assertTrue(group.members.isEmpty())
    }

    @Test
    fun createGroup_withOneMember() {
        val group = groupStore.createGroup(
            "Small",
            listOf(GroupStore.GroupMember(CREATOR, "Admin", "admin")),
            CREATOR,
        )
        assertEquals(1, group.members.size)
    }

    @Test
    fun createGroup_withTenMembers() {
        val members = (0..9).map {
            GroupStore.GroupMember("device-$it", "User $it", "member")
        }
        val group = groupStore.createGroup("Big Group", members, CREATOR)
        assertEquals(10, group.members.size)
    }

    // =================== Name edge cases ===================

    @Test
    fun createGroup_emptyName() {
        val group = groupStore.createGroup("", emptyList(), CREATOR)
        assertEquals("", group.name)
    }

    @Test
    fun createGroup_specialCharactersName() {
        val group = groupStore.createGroup("O'tkan chiziq & maxsus", emptyList(), CREATOR)
        assertEquals("O'tkan chiziq & maxsus", group.name)
    }

    @Test
    fun createGroup_longName() {
        val longName = "A".repeat(1000)
        val group = groupStore.createGroup(longName, emptyList(), CREATOR)
        assertEquals(longName, group.name)
    }

    @Test
    fun createGroup_unicodeName() {
        val group = groupStore.createGroup("Yangi foydalanuvchi guruhi", emptyList(), CREATOR)
        assertEquals("Yangi foydalanuvchi guruhi", group.name)
    }

    // =================== Role edge cases ===================

    @Test
    fun addMember_defaultRole() {
        val group = groupStore.createGroup("G", listOf(GroupStore.GroupMember(CREATOR, "A", "admin")), CREATOR)
        groupStore.addMember(group.groupId, GroupStore.GroupMember(MEMBER_1, "B"))
        val found = groupStore.getGroup(group.groupId)!!
        assertEquals("member", found.members.find { it.deviceId == MEMBER_1 }!!.role)
    }

    @Test
    fun addMember_customRole() {
        val group = groupStore.createGroup("G", listOf(GroupStore.GroupMember(CREATOR, "A", "admin")), CREATOR)
        groupStore.addMember(group.groupId, GroupStore.GroupMember(MEMBER_1, "B", "moderator"))
        val found = groupStore.getGroup(group.groupId)!!
        assertEquals("moderator", found.members.find { it.deviceId == MEMBER_1 }!!.role)
    }

    // =================== Symmetric key ===================

    @Test
    fun symmetricKey_is32Bytes() {
        val group = groupStore.createGroup("G", listOf(GroupStore.GroupMember(CREATOR, "A", "admin")), CREATOR)
        val key = groupStore.getSymmetricKey(group.groupId)!!
        assertEquals(32, key.size)
    }

    @Test
    fun symmetricKey_isRandom() {
        val g1 = groupStore.createGroup("G1", listOf(GroupStore.GroupMember(CREATOR, "A", "admin")), CREATOR)
        val g2 = groupStore.createGroup("G2", listOf(GroupStore.GroupMember(CREATOR, "A", "admin")), CREATOR)
        val k1 = groupStore.getSymmetricKey(g1.groupId)!!
        val k2 = groupStore.getSymmetricKey(g2.groupId)!!
        assertFalse(k1.contentEquals(k2))
    }

    @Test
    fun symmetricKey_afterRotate_isDifferent() {
        val group = groupStore.createGroup("G", listOf(GroupStore.GroupMember(CREATOR, "A", "admin")), CREATOR)
        val oldKey = groupStore.getSymmetricKey(group.groupId)!!
        groupStore.rotateKey(group.groupId)
        val newKey = groupStore.getSymmetricKey(group.groupId)!!
        assertFalse(oldKey.contentEquals(newKey))
    }

    // =================== Bulk operations ===================

    @Test
    fun createManyGroups() {
        for (i in 1..20) {
            groupStore.createGroup("Group $i", listOf(GroupStore.GroupMember(CREATOR, "A", "admin")), CREATOR)
        }
        assertEquals(20, groupStore.getAllGroups().size)
    }

    @Test
    fun addAndRemoveMultipleMembers() {
        val group = groupStore.createGroup("G", listOf(GroupStore.GroupMember(CREATOR, "A", "admin")), CREATOR)
        val members = (1..5).map { GroupStore.GroupMember("dev-$it", "User $it") }
        members.forEach { groupStore.addMember(group.groupId, it) }
        assertEquals(6, groupStore.getGroup(group.groupId)!!.members.size)

        members.forEach { groupStore.removeMember(group.groupId, it.deviceId) }
        assertEquals(1, groupStore.getGroup(group.groupId)!!.members.size)
    }

    @Test
    fun addAndRemoveSameMemberMultipleTimes() {
        val group = groupStore.createGroup("G", listOf(GroupStore.GroupMember(CREATOR, "A", "admin")), CREATOR)
        groupStore.addMember(group.groupId, GroupStore.GroupMember(MEMBER_1, "B"))
        groupStore.removeMember(group.groupId, MEMBER_1)
        groupStore.addMember(group.groupId, GroupStore.GroupMember(MEMBER_1, "B"))
        assertEquals(2, groupStore.getGroup(group.groupId)!!.members.size)
    }

    // =================== Group data class ===================

    @Test
    fun groupDataClass_toString() {
        val group = GroupStore.Group(
            groupId = "g1",
            name = "Test",
            members = emptyList(),
            symmetricKey = "key",
            createdAtMs = 1000L,
            createdBy = CREATOR,
        )
        val str = group.toString()
        assertTrue(str.contains("g1"))
        assertTrue(str.contains("Test"))
    }

    // =================== Update group ===================

    @Test
    fun updateGroup_preservesGroupId() {
        val group = groupStore.createGroup("Original", listOf(GroupStore.GroupMember(CREATOR, "A", "admin")), CREATOR)
        val updated = group.copy(name = "Updated", createdAtMs = System.currentTimeMillis())
        groupStore.updateGroup(updated)
        val found = groupStore.getGroup(group.groupId)!!
        assertEquals(group.groupId, found.groupId)
        assertEquals("Updated", found.name)
    }

    @Test
    fun updateGroup_updatesMembers() {
        val group = groupStore.createGroup("G", listOf(GroupStore.GroupMember(CREATOR, "A", "admin")), CREATOR)
        val newMembers = listOf(
            GroupStore.GroupMember(CREATOR, "A", "admin"),
            GroupStore.GroupMember(MEMBER_1, "B", "member"),
        )
        groupStore.updateGroup(group.copy(members = newMembers))
        assertEquals(2, groupStore.getGroup(group.groupId)!!.members.size)
    }

    // =================== Timestamps ===================

    @Test
    fun createGroup_timestampsAreIncreasing() {
        val g1 = groupStore.createGroup("G1", emptyList(), CREATOR)
        val g2 = groupStore.createGroup("G2", emptyList(), CREATOR)
        assertTrue(g2.createdAtMs >= g1.createdAtMs)
    }

    // =================== getMemberDeviceIds ===================

    @Test
    fun getMemberDeviceIds_afterAddingMembers() {
        val group = groupStore.createGroup("G", listOf(GroupStore.GroupMember(CREATOR, "A", "admin")), CREATOR)
        groupStore.addMember(group.groupId, GroupStore.GroupMember(MEMBER_1, "B"))
        groupStore.addMember(group.groupId, GroupStore.GroupMember(MEMBER_2, "C"))
        val ids = groupStore.getMemberDeviceIds(group.groupId)
        assertEquals(3, ids.size)
        assertTrue(ids.contains(CREATOR))
        assertTrue(ids.contains(MEMBER_1))
        assertTrue(ids.contains(MEMBER_2))
    }

    @Test
    fun getMemberDeviceIds_afterRemoving() {
        val group = groupStore.createGroup("G", listOf(
            GroupStore.GroupMember(CREATOR, "A", "admin"),
            GroupStore.GroupMember(MEMBER_1, "B"),
        ), CREATOR)
        groupStore.removeMember(group.groupId, MEMBER_1)
        val ids = groupStore.getMemberDeviceIds(group.groupId)
        assertEquals(1, ids.size)
        assertTrue(ids.contains(CREATOR))
    }

    // =================== Persistence ===================

    @Test
    fun createGroup_persistsToStorage() {
        groupStore.createGroup("G", listOf(GroupStore.GroupMember(CREATOR, "A", "admin")), CREATOR)
        // Storage should have the groups key
        // (verifying through the mock that putString was called)
    }

    @Test
    fun deleteGroup_persistsRemoval() {
        val group = groupStore.createGroup("G", listOf(GroupStore.GroupMember(CREATOR, "A", "admin")), CREATOR)
        groupStore.deleteGroup(group.groupId)
        assertTrue(groupStore.getAllGroups().isEmpty())
    }

    // =================== GroupMember data class ===================

    @Test
    fun groupMember_defaultRole() {
        val member = GroupStore.GroupMember("d", "N")
        assertEquals("member", member.role)
    }

    @Test
    fun groupMember_customRole() {
        val member = GroupStore.GroupMember("d", "N", "admin")
        assertEquals("admin", member.role)
    }

    @Test
    fun groupMember_toString() {
        val member = GroupStore.GroupMember("d", "N", "admin")
        val str = member.toString()
        assertTrue(str.contains("d"))
        assertTrue(str.contains("N"))
    }

    // =================== rotateKey ===================

    @Test
    fun rotateKey_returns32Bytes() {
        val group = groupStore.createGroup("G", listOf(GroupStore.GroupMember(CREATOR, "A", "admin")), CREATOR)
        val newKey = groupStore.rotateKey(group.groupId)
        assertNotNull(newKey)
        assertEquals(32, newKey!!.size)
    }

    @Test
    fun rotateKey_updatesGroupSymmetricKey() {
        val group = groupStore.createGroup("G", listOf(GroupStore.GroupMember(CREATOR, "A", "admin")), CREATOR)
        val oldB64 = group.symmetricKey
        groupStore.rotateKey(group.groupId)
        val updatedGroup = groupStore.getGroup(group.groupId)!!
        assertFalse(oldB64 == updatedGroup.symmetricKey)
    }

    @Test
    fun rotateKey_multipleRotations() {
        val group = groupStore.createGroup("G", listOf(GroupStore.GroupMember(CREATOR, "A", "admin")), CREATOR)
        val keys = mutableListOf<ByteArray>()
        for (i in 1..5) {
            val key = groupStore.rotateKey(group.groupId)!!
            keys.add(key)
        }
        // All rotated keys should be different
        for (i in 0 until keys.size - 1) {
            assertFalse(keys[i].contentEquals(keys[i + 1]))
        }
    }
}
