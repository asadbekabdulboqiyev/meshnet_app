package com.meshnet.meshnet_app.localnet.rbac

import java.util.concurrent.ConcurrentHashMap

enum class Permission(val key: String, val label: String) {
    // Mesh administration
    MESH_ADMIN("mesh.admin", "Mesh administration"),
    MESH_KICK("mesh.kick", "Kick peers"),
    MESH_BAN("mesh.ban", "Ban peers"),
    MESH_UNBAN("mesh.unban", "Unban peers"),
    MESH_ROLE_ASSIGN("mesh.role.assign", "Assign roles"),

    // LocalNet services
    DNS_MANAGE("dns.manage", "Manage DNS records"),
    DNS_REGISTER("dns.register", "Register hostname"),
    DNS_RESOLVE("dns.resolve", "Resolve hostnames"),

    HTTP_SERVER("http.server", "HTTP server access"),
    HTTP_ADMIN("http.admin", "HTTP server admin"),

    FILE_SHARE("file.share", "Share files"),
    FILE_DOWNLOAD("file.download", "Download files"),
    FILE_DELETE_OWN("file.delete.own", "Delete own files"),
    FILE_DELETE_ANY("file.delete.any", "Delete any files"),

    // Collaboration
    BOARD_CREATE("board.create", "Create whiteboards"),
    BOARD_DRAW("board.draw", "Draw on whiteboards"),
    BOARD_CLEAR("board.clear", "Clear whiteboards"),
    BOARD_ADMIN("board.admin", "Whiteboard admin"),

    DOC_CREATE("doc.create", "Create documents"),
    DOC_EDIT("doc.edit", "Edit documents"),
    DOC_ADMIN("doc.admin", "Document admin"),

    POLL_CREATE("poll.create", "Create polls"),
    POLL_VOTE("poll.vote", "Vote on polls"),
    POLL_ADMIN("poll.admin", "Poll admin"),

    // App distribution
    APP_SHARE("app.share", "Share APKs"),
    APP_INSTALL("app.install", "Install APKs"),
    APP_ADMIN("app.admin", "App store admin"),

    // Gateway
    GATEWAY_START("gateway.start", "Start internet gateway"),
    GATEWAY_STOP("gateway.stop", "Stop internet gateway"),
    GATEWAY_USE("gateway.use", "Use internet gateway"),
    GATEWAY_ADMIN("gateway.admin", "Gateway admin"),

    // Emergency
    EMERGENCY_SEND("emergency.send", "Send emergency alerts"),
    EMERGENCY_ACK("emergency.ack", "Acknowledge emergencies"),
    EMERGENCY_ADMIN("emergency.admin", "Emergency system admin"),

    // Search
    SEARCH_QUERY("search.query", "Search mesh content"),
    SEARCH_INDEX("search.index", "Index content for search"),
    SEARCH_ADMIN("search.admin", "Search admin");

    companion object {
        private val byKey = values().associateBy { it.key }

        fun fromKey(key: String): Permission? = byKey[key]
    }
}

class PermissionSet(private val roles: Map<Role, Set<Permission>> = defaultRolePermissions()) {

    companion object {
        private fun defaultRolePermissions(): Map<Role, Set<Permission>> {
            val map = mutableMapOf<Role, MutableSet<Permission>>()
            Permission.values().forEach { p ->
                when {
                    p.key.startsWith("mesh.") -> map.getOrPut(Role.ADMIN) { mutableSetOf() }.add(p)
                    p.key.startsWith("dns.") -> map.getOrPut(Role.ADMIN) { mutableSetOf() }.add(p)
                    p.key.startsWith("http.") -> map.getOrPut(Role.ADMIN) { mutableSetOf() }.add(p)
                    p.key.startsWith("file.") -> {
                        map.getOrPut(Role.MEMBER) { mutableSetOf() }.add(p)
                        if (p == Permission.FILE_DELETE_ANY) map.getOrPut(Role.ADMIN) { mutableSetOf() }.add(p)
                    }
                    p.key.startsWith("board.") -> {
                        map.getOrPut(Role.MEMBER) { mutableSetOf() }.add(p)
                        if (p == Permission.BOARD_ADMIN) map.getOrPut(Role.ADMIN) { mutableSetOf() }.add(p)
                    }
                    p.key.startsWith("doc.") -> {
                        map.getOrPut(Role.MEMBER) { mutableSetOf() }.add(p)
                        if (p == Permission.DOC_ADMIN) map.getOrPut(Role.ADMIN) { mutableSetOf() }.add(p)
                    }
                    p.key.startsWith("poll.") -> {
                        map.getOrPut(Role.MEMBER) { mutableSetOf() }.add(p)
                        if (p == Permission.POLL_ADMIN) map.getOrPut(Role.ADMIN) { mutableSetOf() }.add(p)
                    }
                    p.key.startsWith("app.") -> {
                        map.getOrPut(Role.MEMBER) { mutableSetOf() }.add(p)
                        if (p == Permission.APP_ADMIN) map.getOrPut(Role.ADMIN) { mutableSetOf() }.add(p)
                    }
                    p.key.startsWith("gateway.") -> {
                        map.getOrPut(Role.MEMBER) { mutableSetOf() }.add(Permission.GATEWAY_USE)
                        if (p != Permission.GATEWAY_USE) map.getOrPut(Role.ADMIN) { mutableSetOf() }.add(p)
                    }
                    p.key.startsWith("emergency.") -> {
                        map.getOrPut(Role.MODERATOR) { mutableSetOf() }.add(p)
                        if (p == Permission.EMERGENCY_ADMIN) map.getOrPut(Role.ADMIN) { mutableSetOf() }.add(p)
                    }
                    p.key.startsWith("search.") -> {
                        map.getOrPut(Role.MEMBER) { mutableSetOf() }.add(p)
                        if (p == Permission.SEARCH_ADMIN) map.getOrPut(Role.ADMIN) { mutableSetOf() }.add(p)
                    }
                }
            }
            // OWNER gets everything
            map[Role.OWNER] = Permission.values().toMutableSet()
            // GUEST gets basic read access
            map[Role.GUEST] = mutableSetOf(Permission.DNS_RESOLVE, Permission.FILE_DOWNLOAD, Permission.BOARD_DRAW, Permission.DOC_EDIT, Permission.POLL_VOTE, Permission.APP_INSTALL, Permission.GATEWAY_USE, Permission.SEARCH_QUERY)
            return map.mapValues { it.value.toMutableSet() }
        }
    }

    fun hasPermission(role: Role, permission: Permission): Boolean {
        return roles.getOrDefault(role, emptySet()).contains(permission)
    }

    fun getPermissions(role: Role): Set<Permission> = roles.getOrDefault(role, emptySet())

    fun effectivePermissions(roles: Set<Role>): Set<Permission> {
        val perms = mutableSetOf<Permission>()
        roles.forEach { perms.addAll(getPermissions(it)) }
        return perms
    }

    fun canAccess(roles: Set<Role>, permission: Permission): Boolean = effectivePermissions(roles).contains(permission)
}