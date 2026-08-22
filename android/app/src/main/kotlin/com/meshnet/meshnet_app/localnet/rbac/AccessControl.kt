package com.meshnet.meshnet_app.localnet.rbac

import java.util.concurrent.ConcurrentHashMap

import kotlin.concurrent.thread

/**
 * RBAC Access Control for LocalNet mesh resources.
 * Manages device roles per resource (board, doc, poll, gateway, etc.)
 * and enforces permissions.
 *
 * CRITICAL: Role grants can be cryptographically signed via ECDSA P-256
 * using SigningIdentity. When a signed grant is received, call
 * applySignedRoleGrant() to verify and apply it.
 *
 * Wire protocol: ROLE_GRANT (0x77) — payload "grantId|roleName|targetDeviceId|grantedAtMs|signatureB64"
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

    /** Verify and apply a signed role grant.
     *  Payload: "grantId|roleName|targetDeviceId|grantedAtMs|signatureB64"
     *  Signature: base64 DER ECDSA signature over payload bytes (excluding signature itself)
     *  Returns true if signature validates and role grant was applied.
     *
     *  Example usage when receiving ROLE_GRANT (0x77) wire protocol message:
     *    val payload = "${grantId}|${role.name}|${targetDeviceId}|${grantedAtMs}"
     *    val signature = signatureB64 from the message
     *    accessControl.applySignedRoleGrant(
     *        signerPublicKeyB64, grantId, role.name, targetDeviceId, grantedAtMs, signature
     *    )
     */
    fun applySignedRoleGrant(
        signerPublicKeyB64: String,
        grantId: String,
        roleName: String,
        targetDeviceId: String,
        grantedAtMs: Long,
        signatureB64: String
    ): Boolean {
        try {
            // 1. Construct the canonical payload (excluding signature)
            val payload = "${grantId}|${roleName}|${targetDeviceId}|${grantedAtMs}"

            // 2. Verify signature using SigningIdentity (same package, accessible)
            val payloadBytes = payload.toByteArray(Charsets.UTF_8)
            val isValid = SigningIdentity.verify(
                signerPublicKeyB64,
                payloadBytes,
                signatureB64
            )
            if (!isValid) return false

            // 3. Apply the role grant if signature is valid
            val role = Role.values().firstOrNull { it.name.equals(roleName, ignoreCase = true) }
            if (role == null) return false

            // Set device-wide role
            setDeviceRole(targetDeviceId, role)

            // Set per-resource role (mesh as resourceType for device-wide)
            setRole("mesh", targetDeviceId, targetDeviceId, role)

            // 4. Persist the grant note: in a full impl, would write to DB role_grants table
            // grantRole(grantId, targetDeviceId, role, signerDeviceId)

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

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

/** Holder for AccessControl singleton management. */
object AccessControlHolder {
    @Volatile private var INSTANCE: AccessControl? = null

    fun getInstance(): AccessControl = INSTANCE ?: synchronized(this) { INSTANCE ?: AccessControl().also { INSTANCE = it } }

    fun setInstance(instance: AccessControl) { INSTANCE = instance }
    fun resetInstance() { INSTANCE = null }
}