package com.meshnet.meshnet_app.localnet.rbac

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * RBAC Access Control for LocalNet mesh resources.
 * Manages device roles per resource (board, doc, poll, gateway, etc.)
 * and enforces permissions.
 */
class AccessControl {

    // resourceType -> resourceId -> deviceId -> Role
    private val roleAssignments = ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, Role>>>()
    private val permissionSet = PermissionSet()

    // Device default role (mesh-wide)
    private val deviceRoles = ConcurrentHashMap<String, Role>()

    // Role change listeners
    private val listeners = mutableListOf<RoleChangeListener>()

    interface RoleChangeListener {
        fun onRoleChanged(deviceId: String, resourceType: String, resourceId: String, oldRole: Role?, newRole: Role)
    }

    fun addListener(listener: RoleChangeListener) = listeners.add(listener)
    fun removeListener(listener: RoleChangeListener) = listeners.remove(listener)

    private fun notify(deviceId: String, resourceType: String, resourceId: String, old: Role?, new: Role) {
        listeners.forEach { it.onRoleChanged(deviceId, resourceType, resourceId, old, new) }
    }

    // --- Mesh-wide default role ---

    fun setDeviceRole(deviceId: String, role: Role) {
        val old = deviceRoles.put(deviceId, role)
        if (old != role) notify(deviceId, "mesh", "global", old, role)
    }

    fun getDeviceRole(deviceId: String): Role = deviceRoles[deviceId] ?: Role.GUEST

    // --- Per-resource roles ---

    fun setRole(resourceType: String, resourceId: String, deviceId: String, role: Role) {
        val resourceMap = roleAssignments.computeIfAbsent(resourceType) { ConcurrentHashMap() }
        val deviceMap = resourceMap.computeIfAbsent(resourceId) { ConcurrentHashMap() }
        val old = deviceMap.put(deviceId, role)
        if (old != role) notify(deviceId, resourceType, resourceId, old, role)
    }

    fun getRole(resourceType: String, resourceId: String, deviceId: String): Role {
        val resourceMap = roleAssignments[resourceType]
        if (resourceMap == null) return deviceRoles[deviceId] ?: Role.GUEST
        val deviceMap = resourceMap[resourceId]
        if (deviceMap == null) return deviceRoles[deviceId] ?: Role.GUEST
        val role = deviceMap[deviceId]
        return role ?: deviceRoles[deviceId] ?: Role.GUEST
    }

    fun getRoles(resourceType: String, resourceId: String): Map<String, Role> {
        val resourceMap = roleAssignments[resourceType]
        if (resourceMap == null) return emptyMap()
        val deviceMap = resourceMap[resourceId]
        return deviceMap?.toMap() ?: emptyMap()
    }

    fun removeRole(resourceType: String, resourceId: String, deviceId: String) {
        val resourceMap = roleAssignments[resourceType]
        if (resourceMap == null) return
        val deviceMap = resourceMap[resourceId]
        if (deviceMap == null) return
        val old = deviceMap.remove(deviceId)
        if (old != null) {
            notify(deviceId, resourceType, resourceId, old, getRole(resourceType, resourceId, deviceId))
            if (deviceMap.isEmpty()) resourceMap.remove(resourceId)
            if (resourceMap.isEmpty()) roleAssignments.remove(resourceType)
        }
    }

    // --- Permission checks ---

    fun hasPermission(deviceId: String, permission: Permission): Boolean {
        val role = getDeviceRole(deviceId)
        return permissionSet.hasPermission(role, permission)
    }

    fun hasPermission(deviceId: String, resourceType: String, resourceId: String, permission: Permission): Boolean {
        val role = getRole(resourceType, resourceId, deviceId)
        return permissionSet.hasPermission(role, permission)
    }

    fun hasAnyPermission(deviceId: String, resourceType: String, resourceId: String, permissions: Set<Permission>): Boolean {
        val role = getRole(resourceType, resourceId, deviceId)
        return permissions.any { permissionSet.hasPermission(role, it) }
    }

    fun canAccess(deviceId: String, resourceType: String, resourceId: String, permission: Permission): Boolean {
        return hasPermission(deviceId, resourceType, resourceId, permission)
    }

    // --- Owner assignment (first creator becomes owner) ---

    fun assignOwnerIfEmpty(resourceType: String, resourceId: String, creatorDeviceId: String): Boolean {
        val current = getRole(resourceType, resourceId, creatorDeviceId)
        if (current == Role.GUEST || current == Role.MEMBER) {
            setRole(resourceType, resourceId, creatorDeviceId, Role.OWNER)
            return true
        }
        return false
    }

    // --- Resource transfer ---

    fun transferOwnership(resourceType: String, resourceId: String, fromDeviceId: String, toDeviceId: String): Boolean {
        val fromRole = getRole(resourceType, resourceId, fromDeviceId)
        if (fromRole != Role.OWNER) return false
        setRole(resourceType, resourceId, fromDeviceId, Role.ADMIN)
        setRole(resourceType, resourceId, toDeviceId, Role.OWNER)
        return true
    }

    // --- Ban/unban ---

    fun ban(deviceId: String) {
        setDeviceRole(deviceId, Role.BANNED)
    }

    fun unban(deviceId: String) {
        setDeviceRole(deviceId, Role.GUEST)
    }

    fun isBanned(deviceId: String): Boolean = getDeviceRole(deviceId) == Role.BANNED

    // --- Serialization for persistence ---

    fun snapshot(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        map["deviceRoles"] = deviceRoles.mapValues { it.value.name }
        val ra = mutableMapOf<String, Any>()
        roleAssignments.forEach { (rType, resMap) ->
            val rm = mutableMapOf<String, Any>()
            resMap.forEach { (resId, devMap) ->
                rm[resId] = devMap.mapValues { it.value.name }
            }
            ra[rType] = rm
        }
        map["roleAssignments"] = ra
        return map
    }

    fun restore(snapshot: Map<String, Any>) {
        deviceRoles.clear()
        (snapshot["deviceRoles"] as? Map<String, String>)?.forEach { (k, v) -> deviceRoles[k] = Role.fromString(v) }
        roleAssignments.clear()
        (snapshot["roleAssignments"] as? Map<String, Any>)?.forEach { (rType, resMap) ->
            val rm = ConcurrentHashMap<String, ConcurrentHashMap<String, Role>>()
            (resMap as? Map<String, Any>)?.forEach { (resId, devMap) ->
                val dm = ConcurrentHashMap<String, Role>()
                (devMap as? Map<String, String>)?.forEach { (k, v) -> dm[k] = Role.fromString(v) }
                rm[resId] = dm
            }
            roleAssignments[rType] = rm
        }
    }
}

object AccessControlHolder {
    @Volatile private var INSTANCE: AccessControl? = null

    fun getInstance(): AccessControl = INSTANCE ?: synchronized(this) { INSTANCE ?: AccessControl().also { INSTANCE = it } }

    fun setInstance(instance: AccessControl) { INSTANCE = instance }
    fun resetInstance() { INSTANCE = null }
}