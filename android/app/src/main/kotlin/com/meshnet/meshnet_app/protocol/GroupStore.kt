package com.meshnet.meshnet_app.protocol

import android.content.Context
import com.meshnet.meshnet_app.crypto.MeshCrypto
import com.meshnet.meshnet_app.storage.MeshDatabase
import java.security.SecureRandom

class GroupStore {

    companion object {
        private const val TAG = "GroupStore"
    }

    data class GroupMember(
        val deviceId: String,
        val displayName: String,
        val role: String = "member",
    )

    data class Group(
        val groupId: String,
        val name: String,
        val members: List<GroupMember>,
        val symmetricKey: String,
        val createdAtMs: Long,
        val createdBy: String,
    )

    private val db: MeshDatabase

    /** Production constructor */
    constructor(context: Context) {
        db = MeshDatabase.getInstance(context)
    }

    /** Testing constructor */
    constructor(database: MeshDatabase) {
        db = database
    }

    fun createGroup(name: String, members: List<GroupMember>, createdBy: String): Group {
        val groupId = java.util.UUID.randomUUID().toString()
        val keyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val group = Group(
            groupId = groupId,
            name = name,
            members = members,
            symmetricKey = MeshCrypto.b64(keyBytes),
            createdAtMs = System.currentTimeMillis(),
            createdBy = createdBy,
        )
        db.insertGroup(group)
        return group
    }

    fun getGroup(groupId: String): Group? = db.getGroup(groupId)

    fun getAllGroups(): List<Group> = db.getAllGroups()

    fun updateGroup(group: Group) {
        db.insertGroup(group)
    }

    fun deleteGroup(groupId: String) {
        db.deleteGroup(groupId)
    }

    fun addMember(groupId: String, member: GroupMember) {
        val group = db.getGroup(groupId) ?: return
        if (group.members.any { it.deviceId == member.deviceId }) return
        val updated = group.copy(members = group.members + member)
        db.insertGroup(updated)
    }

    fun removeMember(groupId: String, deviceId: String) {
        val group = db.getGroup(groupId) ?: return
        val updated = group.copy(members = group.members.filter { it.deviceId != deviceId })
        db.insertGroup(updated)
    }

    fun getMemberDeviceIds(groupId: String): List<String> {
        return db.getGroupMembers(groupId).map { it.deviceId }
    }

    fun getSymmetricKey(groupId: String): ByteArray? {
        return db.getGroup(groupId)?.symmetricKey?.let { MeshCrypto.unb64(it) }
    }

    fun rotateKey(groupId: String): ByteArray? {
        val group = db.getGroup(groupId) ?: return null
        val newKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val updated = group.copy(symmetricKey = MeshCrypto.b64(newKey))
        db.insertGroup(updated)
        return newKey
    }
}
