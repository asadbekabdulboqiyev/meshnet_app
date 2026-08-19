package com.meshnet.meshnet_app.protocol

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.meshnet.meshnet_app.crypto.MeshCrypto
import java.security.SecureRandom

class GroupStore(context: Context) {

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

    private val prefs: SharedPreferences =
        context.getSharedPreferences("meshnet_groups", Context.MODE_PRIVATE)
    private val gson = Gson()

    private fun load(): MutableMap<String, Group> {
        val json = prefs.getString("groups", null) ?: return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, Group>>() {}.type
            gson.fromJson(json, type) ?: mutableMapOf()
        } catch (e: Exception) {
            Log.e(TAG, "Load error: ${e.message}")
            mutableMapOf()
        }
    }

    private fun save(groups: MutableMap<String, Group>) {
        prefs.edit().putString("groups", gson.toJson(groups)).apply()
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
        val groups = load()
        groups[groupId] = group
        save(groups)
        return group
    }

    fun getGroup(groupId: String): Group? = load()[groupId]

    fun getAllGroups(): List<Group> = load().values.toList()

    fun updateGroup(group: Group) {
        val groups = load()
        groups[group.groupId] = group
        save(groups)
    }

    fun deleteGroup(groupId: String) {
        val groups = load()
        groups.remove(groupId)
        save(groups)
    }

    fun addMember(groupId: String, member: GroupMember) {
        val group = load()[groupId] ?: return
        if (group.members.any { it.deviceId == member.deviceId }) return
        val updated = group.copy(members = group.members + member)
        updateGroup(updated)
    }

    fun removeMember(groupId: String, deviceId: String) {
        val group = load()[groupId] ?: return
        val updated = group.copy(members = group.members.filter { it.deviceId != deviceId })
        updateGroup(updated)
    }

    fun getMemberDeviceIds(groupId: String): List<String> {
        return load()[groupId]?.members?.map { it.deviceId } ?: emptyList()
    }

    fun getSymmetricKey(groupId: String): ByteArray? {
        return load()[groupId]?.symmetricKey?.let { MeshCrypto.unb64(it) }
    }

    fun rotateKey(groupId: String): ByteArray? {
        val group = load()[groupId] ?: return null
        val newKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val updated = group.copy(symmetricKey = MeshCrypto.b64(newKey))
        updateGroup(updated)
        return newKey
    }
}
