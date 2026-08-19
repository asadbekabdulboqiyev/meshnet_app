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
 * GroupStore testlari: SharedPreferences mock bilan.
 * createGroup, getAllGroups, getSymmetricKey, add/remove member,
 * updateGroup, deleteGroup, rotateKey.
 */
class GroupStoreTest {

    private lateinit var mockContext: Context
    private lateinit var groupStore: GroupStore

    private val CREATOR_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
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

    @Test
    fun createGroup_createsGroupWithCorrectFields() {
        val members = listOf(
            GroupStore.GroupMember(CREATOR_ID, "Admin", "admin"),
            GroupStore.GroupMember(MEMBER_1, "User1", "member"),
        )

        val group = groupStore.createGroup("Test Guruh", members, CREATOR_ID)

        assertNotNull(group.groupId)
        assertTrue(group.groupId.isNotEmpty())
        assertEquals("Test Guruh", group.name)
        assertEquals(2, group.members.size)
        assertEquals(CREATOR_ID, group.members[0].deviceId)
        assertEquals("admin", group.members[0].role)
        assertEquals(MEMBER_1, group.members[1].deviceId)
        assertEquals("member", group.members[1].role)
        assertNotNull(group.symmetricKey)
        assertTrue(group.symmetricKey.isNotEmpty())
        assertTrue(group.createdAtMs > 0)
        assertEquals(CREATOR_ID, group.createdBy)

        // Symmetric key 32 byte base64 bo'lishi kerak
        val keyBytes = MeshCrypto.unb64(group.symmetricKey)
        assertEquals(32, keyBytes.size)
    }

    @Test
    fun createGroup_generatesUniqueIdsAndKeys() {
        val members = listOf(GroupStore.GroupMember(CREATOR_ID, "Admin", "admin"))

        val group1 = groupStore.createGroup("Guruh 1", members, CREATOR_ID)
        val group2 = groupStore.createGroup("Guruh 2", members, CREATOR_ID)

        assertFalse(group1.groupId == group2.groupId)
        assertFalse(group1.symmetricKey == group2.symmetricKey)
    }

    @Test
    fun getAllGroups_returnsCreatedGroups() {
        // Avval bo'sh
        var all = groupStore.getAllGroups()
        assertTrue(all.isEmpty())

        // 3 ta guruh yaratamiz
        val g1 = groupStore.createGroup("Birinchi", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        val g2 = groupStore.createGroup("Ikkinchi", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        val g3 = groupStore.createGroup("Uchinchi", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)

        all = groupStore.getAllGroups()
        assertEquals(3, all.size)

        val names = all.map { it.name }.toSet()
        assertEquals(setOf("Birinchi", "Ikkinchi", "Uchinchi"), names)

        val ids = all.map { it.groupId }.toSet()
        assertEquals(setOf(g1.groupId, g2.groupId, g3.groupId), ids)
    }

    @Test
    fun getSymmetricKey_returnsKeyForExistingGroup() {
        val group = groupStore.createGroup(
            "Key Test",
            listOf(GroupStore.GroupMember(CREATOR_ID, "Admin", "admin")),
            CREATOR_ID,
        )

        val key = groupStore.getSymmetricKey(group.groupId)
        assertNotNull(key)
        assertEquals(32, key!!.size)

        // Key Gruppening symmetricKey si bilan mos kelishi kerak
        val expected = MeshCrypto.unb64(group.symmetricKey)
        assertArrayEquals(expected, key)
    }

    @Test
    fun getSymmetricKey_returnsNullForUnknownGroup() {
        val key = groupStore.getSymmetricKey("unknown-group-id")
        assertNull(key)
    }

    @Test
    fun getGroup_returnsGroupById() {
        val created = groupStore.createGroup(
            "Topiladigan",
            listOf(GroupStore.GroupMember(CREATOR_ID, "Admin", "admin")),
            CREATOR_ID,
        )

        val found = groupStore.getGroup(created.groupId)
        assertNotNull(found)
        assertEquals(created.groupId, found!!.groupId)
        assertEquals("Topiladigan", found.name)
    }

    @Test
    fun getGroup_returnsNullForUnknownId() {
        val found = groupStore.getGroup("bunday-yo'q")
        assertNull(found)
    }

    @Test
    fun updateGroup_updatesExistingGroup() {
        val group = groupStore.createGroup(
            "Eski Nom",
            listOf(GroupStore.GroupMember(CREATOR_ID, "Admin", "admin")),
            CREATOR_ID,
        )

        val updated = group.copy(name = "Yangi Nom")
        groupStore.updateGroup(updated)

        val found = groupStore.getGroup(group.groupId)
        assertNotNull(found)
        assertEquals("Yangi Nom", found!!.name)
        assertEquals(group.groupId, found.groupId) // ID o'zgarmasligi kerak
    }

    @Test
    fun deleteGroup_removesGroup() {
        val group = groupStore.createGroup(
            "O'chiriladigan",
            listOf(GroupStore.GroupMember(CREATOR_ID, "Admin", "admin")),
            CREATOR_ID,
        )

        assertNotNull(groupStore.getGroup(group.groupId))

        groupStore.deleteGroup(group.groupId)

        assertNull(groupStore.getGroup(group.groupId))
        assertFalse(groupStore.getAllGroups().any { it.groupId == group.groupId })
    }

    @Test
    fun addMember_addsNewMember() {
        val group = groupStore.createGroup(
            "A'zo Test",
            listOf(GroupStore.GroupMember(CREATOR_ID, "Admin", "admin")),
            CREATOR_ID,
        )

        val newMember = GroupStore.GroupMember(MEMBER_1, "Yangi A'zo", "member")
        groupStore.addMember(group.groupId, newMember)

        val found = groupStore.getGroup(group.groupId)
        assertNotNull(found)
        assertEquals(2, found!!.members.size)
        assertTrue(found.members.any { it.deviceId == MEMBER_1 })
        assertEquals("Yangi A'zo", found.members.find { it.deviceId == MEMBER_1 }!!.displayName)
    }

    @Test
    fun addMember_doesNotDuplicate() {
        val group = groupStore.createGroup(
            "Nodup",
            listOf(GroupStore.GroupMember(CREATOR_ID, "Admin", "admin")),
            CREATOR_ID,
        )

        val member = GroupStore.GroupMember(MEMBER_1, "User", "member")
        groupStore.addMember(group.groupId, member)
        groupStore.addMember(group.groupId, member) // Takror

        val found = groupStore.getGroup(group.groupId)
        assertEquals(2, found!!.members.size) // Faqat 2 ta
    }

    @Test
    fun removeMember_removesExistingMember() {
        val group = groupStore.createGroup(
            "Remove Test",
            listOf(
                GroupStore.GroupMember(CREATOR_ID, "Admin", "admin"),
                GroupStore.GroupMember(MEMBER_1, "User1", "member"),
            ),
            CREATOR_ID,
        )

        groupStore.removeMember(group.groupId, MEMBER_1)

        val found = groupStore.getGroup(group.groupId)
        assertNotNull(found)
        assertEquals(1, found!!.members.size)
        assertEquals(CREATOR_ID, found.members[0].deviceId)
        assertFalse(found.members.any { it.deviceId == MEMBER_1 })
    }

    @Test
    fun removeMember_removingNonExistentNoOp() {
        val group = groupStore.createGroup(
            "NoOp Remove",
            listOf(GroupStore.GroupMember(CREATOR_ID, "Admin", "admin")),
            CREATOR_ID,
        )

        groupStore.removeMember(group.groupId, MEMBER_1) // Mavjud emas

        val found = groupStore.getGroup(group.groupId)
        assertEquals(1, found!!.members.size)
    }

    @Test
    fun getMemberDeviceIds_returnsCorrectIds() {
        val group = groupStore.createGroup(
            "Member IDs",
            listOf(
                GroupStore.GroupMember(CREATOR_ID, "Admin", "admin"),
                GroupStore.GroupMember(MEMBER_1, "User1", "member"),
                GroupStore.GroupMember(MEMBER_2, "User2", "member"),
            ),
            CREATOR_ID,
        )

        val ids = groupStore.getMemberDeviceIds(group.groupId)
        assertEquals(3, ids.size)
        assertTrue(ids.contains(CREATOR_ID))
        assertTrue(ids.contains(MEMBER_1))
        assertTrue(ids.contains(MEMBER_2))
    }

    @Test
    fun getMemberDeviceIds_unknownGroupReturnsEmpty() {
        val ids = groupStore.getMemberDeviceIds("unknown")
        assertTrue(ids.isEmpty())
    }

    @Test
    fun rotateKey_changesSymmetricKey() {
        val group = groupStore.createGroup(
            "Rotate Test",
            listOf(GroupStore.GroupMember(CREATOR_ID, "Admin", "admin")),
            CREATOR_ID,
        )

        val oldKey = groupStore.getSymmetricKey(group.groupId)
        assertNotNull(oldKey)

        val newKey = groupStore.rotateKey(group.groupId)
        assertNotNull(newKey)
        assertEquals(32, newKey!!.size)

        // Eski key bilan mos kelmasligi kerak
        assertFalse(oldKey!!.contentEquals(newKey))

        // Yangi key store da saqlangan
        val storedKey = groupStore.getSymmetricKey(group.groupId)
        assertArrayEquals(newKey, storedKey!!)

        // Guruh obyekti ham yangilanib turishi kerak
        val updatedGroup = groupStore.getGroup(group.groupId)
        assertNotNull(updatedGroup)
        assertEquals(MeshCrypto.b64(newKey), updatedGroup!!.symmetricKey)
    }

    @Test
    fun rotateKey_unknownGroupReturnsNull() {
        val result = groupStore.rotateKey("unknown-group")
        assertNull(result)
    }

    @Test
    fun multipleGroups_independent() {
        val g1 = groupStore.createGroup("Guruh A", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        val g2 = groupStore.createGroup("Guruh B", listOf(GroupStore.GroupMember(MEMBER_1, "B", "admin")), MEMBER_1)

        // Har biri o'z a'zolariga ega
        assertEquals(1, groupStore.getMemberDeviceIds(g1.groupId).size)
        assertEquals(1, groupStore.getMemberDeviceIds(g2.groupId).size)

        // Har biri o'z keyiga ega
        val k1 = groupStore.getSymmetricKey(g1.groupId)
        val k2 = groupStore.getSymmetricKey(g2.groupId)
        assertFalse(k1!!.contentEquals(k2!!))

        // Biri o'chirilsa digeri qoladi
        groupStore.deleteGroup(g1.groupId)
        assertNull(groupStore.getGroup(g1.groupId))
        assertNotNull(groupStore.getGroup(g2.groupId))
    }

    @Test
    fun groupMemberRoleDefaultsToMember() {
        val group = groupStore.createGroup(
            "Role Test",
            listOf(GroupStore.GroupMember(MEMBER_1, "Oddiy A'zo")), // role berilmagan
            CREATOR_ID,
        )

        assertEquals("member", group.members[0].role)
    }

    @Test
    fun groupCreatedAtIsRecent() {
        val before = System.currentTimeMillis()
        val group = groupStore.createGroup("Time Test", listOf(GroupStore.GroupMember(CREATOR_ID, "A", "admin")), CREATOR_ID)
        val after = System.currentTimeMillis()

        assertTrue(group.createdAtMs >= before)
        assertTrue(group.createdAtMs <= after)
    }
}
